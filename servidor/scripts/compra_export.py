#!/usr/bin/env python3
"""Listas de la familia (vía Home Assistant) → compra.json para el marco del salón («rutas.compra_json»).

Lo lanza familia-compra.timer cada minuto; nginx sirve el fichero en una ruta secreta (ver
despliegue/nginx-ejemplo.conf). Solo escribe si algo cambia y nunca mientras HA no esté arrancado del todo (se
queda el último fichero bueno). Solo lectura de HA.

- «items»: la lista de la compra (Bring!), formato antiguo que leen las versiones anteriores de la app.
- «coche»: modelo (de la configuración), batería, autonomía, si está enchufado o cargando y cuándo informó el coche
  por última vez; además cuentakilómetros, presión de las ruedas (bar) y si carga hasta el 100 %. Si el coche duerme
  y HA no da la batería, los últimos datos buenos con «dormido»: true y «dormido_desde».
- «moto»: moto eléctrica (de «moto» en la configuración; por defecto la integración de Silence): batería, autonomía,
  si carga, cuentakilómetros, si tiene la alarma puesta, estado (IDLE…) y cuándo informó. Sin ubicación.
- «frecuentes»: los 3 productos más pedidos que aún no están en la lista («rutas.compra_frecuentes», de
  compra_sync.py), sin los de «rutas.compra_ignorar».
- «casa»: termostato (temperatura, consigna, modo, humedad, pila), temperatura de las estancias (p. ej. sensores de
  los Echo), toldo (sacado en %; si «invertido», HA lo da al revés), la música que suena (o la última pausada) en un
  Echo, las zonas de la climatización («zonas», p. ej. Airzone) y el termo eléctrico («termo», p. ej. Ariston).
- «seguridad»: solo lectura, cerradura (estado, batería, si está crítica o cargando y, con sensor, la puerta) y alarma
  (estado de HA: disarmed, armed_away…), de «seguridad» en la configuración. Lo que falte, null.
- «listas»: cada lista de «listas_marco» con nombre, color y elementos; las tareas de Todoist con su fecha
  («vence»), ordenadas: primero las que tienen fecha (las más próximas antes) y luego el resto.

Uso: python3 -I scripts/compra_export.py
"""
import json, os, sys, tempfile, urllib.request
from datetime import datetime, timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import familia  # noqa: E402

CFG = familia.config()
HA = familia.ha_url()
OUT = familia.ruta("compra_json")
TOKEN = familia.ha_token()
BRING = familia.ha().get("compra_bring", "")
CASA = CFG.get("casa", {})


def ha_listo():
    """HA contesta y ya ha arrancado del todo («RUNNING»). Mientras se reinicia, sus entidades no existen o salen
    vacías: escribir entonces dejaría el marco sin coche, seguridad ni listas hasta la siguiente descarga."""
    try:
        req = urllib.request.Request(f"{HA}/api/config", headers={"Authorization": f"Bearer {TOKEN}"})
        return json.load(urllib.request.urlopen(req, timeout=10)).get("state") == "RUNNING"
    except Exception:
        return False


if not ha_listo():
    raise SystemExit(0)  # el marco sigue con el último compra.json bueno


def entidad(v):
    """«todo.algo» tal cual; «compra_bring», «tareas_todoist»… se buscan en «home_assistant»."""
    return v if "." in v else familia.entidad(v)


LISTS = [(entidad(l["entidad"]), l["nombre"], l.get("color", "#2E9E5B"))  # (entidad, nombre en el marco, color)
         for l in CFG.get("listas_marco", [])]


def items(entity):
    req = urllib.request.Request(f"{HA}/api/services/todo/get_items?return_response",
                                 data=json.dumps({"entity_id": entity, "status": "needs_action"}).encode(),
                                 headers={"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"},
                                 method="POST")
    return json.load(urllib.request.urlopen(req, timeout=20))["service_response"][entity]["items"]


listas = []
compra = None
for entity, nombre, color in LISTS:
    try:
        its = [{"producto": i["summary"], "detalle": i.get("description") or "", "vence": i.get("due") or ""} for i in items(entity)]
    except Exception:
        continue  # una lista caída no tumba a las demás; el marco sigue con su copia
    its.sort(key=lambda x: (x["vence"] == "", x["vence"]))
    listas.append({"nombre": nombre, "color": color, "items": its})
    if entity == BRING and compra is None:
        compra = its
if compra is None:
    compra = next((l["items"] for l in listas if l["nombre"] == "Lista de la compra"), [])

def state(entity):
    req = urllib.request.Request(f"{HA}/api/states/{entity}", headers={"Authorization": f"Bearer {TOKEN}"})
    return json.load(urllib.request.urlopen(req, timeout=20))["state"]


def full(entity):
    req = urllib.request.Request(f"{HA}/api/states/{entity}", headers={"Authorization": f"Bearer {TOKEN}"})
    return json.load(urllib.request.urlopen(req, timeout=20))


RUEDAS =("delantero_izquierdo", "delantero_derecho", "trasero_izquierdo", "trasero_derecho")


def entidades_coche(car_cfg):
    """Entidades del coche en HA. Por defecto, las de la integración original (sensor.<prefijo>_nivel_de_bateria…);
    «coche.entidades» cambia cualquiera (p. ej. con Tesla Fleet) o la anula con null si el coche no la tiene."""
    car = car_cfg["prefijo_sensores"]
    ent = {"bateria": f"sensor.{car}_nivel_de_bateria", "autonomia": f"sensor.{car}_autonomia",
           "cargando": f"binary_sensor.{car}_cargando", "enchufe": f"binary_sensor.{car}_enchufe",
           "informado": f"sensor.{car}_ultima_actualizacion", "km": f"sensor.{car}_cuentakilometros",
           "cargar_100": f"switch.{car}_charge_to_full", "potencia": f"sensor.{car}_battery_power",
           **{f"rueda_{r}": f"sensor.{car}_presion_neumatico_{r}" for r in RUEDAS}}
    ent.update({k: v for k, v in (car_cfg.get("entidades") or {}).items() if not k.startswith("_")})
    return ent


def numero(v):
    return float(v) if v not in (None, "unknown", "unavailable", "") else None


# Presión de las ruedas a bar según la unidad de HA: kPa (integración original), psi (Tesla Fleet) o bar
A_BAR = {"kpa": 0.01, "psi": 0.0689476, "bar": 1.0}


def presion_bar(s):
    v = numero(s["state"])
    factor = A_BAR.get(str(s["attributes"].get("unit_of_measurement") or "kPa").lower(), 0.01)
    return round(v * factor, 1)


coche = None
try:
    car_cfg = CFG.get("coche") or {}
    ent = entidades_coche(car_cfg)
    num = lambda k: numero(state(ent[k]))
    # Imprescindibles: batería, autonomía, cargando y enchufe. «cargando» vale como binary_sensor («on») o como sensor
    # del estado de carga («charging»/«starting», Tesla Fleet).
    coche = {"modelo": car_cfg.get("modelo") or "",
             "bateria": num("bateria"), "autonomia_km": num("autonomia"),
             "cargando": state(ent["cargando"]) in ("on", "charging", "starting"),
             "enchufado": state(ent["enchufe"]) == "on", "informado": None}
    # El resto, cada uno por su lado: lo que el coche no tenga (null en «entidades» o error) no tumba lo demás.
    if ent.get("informado"):
        try:
            coche["informado"] = state(ent["informado"])
        except Exception:
            pass
    if ent.get("km"):
        try:
            coche["km"] = num("km")
        except Exception:
            pass
    try:
        if all(ent.get(f"rueda_{r}") for r in RUEDAS):
            coche["ruedas_bar"] = [presion_bar(full(ent[f"rueda_{r}"])) for r in RUEDAS]
    except Exception:
        pass
    if ent.get("cargar_100"):
        try:
            # Un interruptor («on») o un límite de carga en % (number de Tesla Fleet): al 100 % = cargar hasta el final
            v = state(ent["cargar_100"])
            coche["cargar_100"] = v == "on" or (numero(v) or 0) >= 100
        except Exception:
            pass
    if ent.get("potencia"):
        try:
            p = full(ent["potencia"])
            potencia = numero(p["state"])  # 0 cuando la carga está en pausa
            if potencia is not None and p["attributes"].get("unit_of_measurement") == "kW":
                potencia *= 1000  # Tesla Fleet da kW; la integración original, W
            coche["potencia_kw"] = round(abs(potencia) / 1000, 1) if potencia and abs(potencia) >= 100 else None
        except Exception:
            pass
except Exception:
    coche = None  # sin coche, el resto del fichero sigue igual


# Moto eléctrica: por defecto, las entidades de la integración de Silence (HACS lorenzo-deluca/homeassistant-silence,
# sensor.silence_*); «moto.entidades» cambia cualquiera o la anula con null. La ubicación no se publica nunca.
MOTO_ENT = {"bateria": "sensor.silence_batterysoc", "autonomia": "sensor.silence_range",
            "cargando": "sensor.silence_charging", "km": "sensor.silence_odometer",
            "alarma": "sensor.silence_alarmactivated", "estado": "sensor.silence_status",
            "informado": "sensor.silence_lastreporttime"}
SI = ("1", "on", "true", "charging")


def moto_export(cfg):
    """{modelo, bateria, autonomia_km, cargando, km, alarma, estado, informado}; None sin «moto» o sin batería."""
    if not cfg:
        return None
    ent = {**MOTO_ENT, **{k: v for k, v in (cfg.get("entidades") or {}).items() if not k.startswith("_")}}

    def leer(k, conv=lambda v: v):
        try:
            v = state(ent[k]) if ent.get(k) else None
            return None if v in (None, "", "unknown", "unavailable") else conv(v)
        except Exception:
            return None

    m = {"modelo": cfg.get("modelo") or "Moto", "bateria": leer("bateria", float),
         "autonomia_km": leer("autonomia", float), "cargando": leer("cargando", lambda v: v.lower() in SI),
         "km": leer("km", float), "alarma": leer("alarma", lambda v: v.lower() in SI),
         "estado": leer("estado"), "informado": leer("informado")}
    return m if m["bateria"] is not None else None


moto = moto_export(CFG.get("moto"))


def anterior():
    """El compra.json que ya hay publicado ({} si no hay o está roto)."""
    try:
        return json.load(open(OUT))
    except (FileNotFoundError, ValueError):
        return {}


# Coche dormido: tras reiniciar HA (o al rato de dormirse), Tesla Fleet deja batería y autonomía en «unknown» hasta que
# el coche despierta, y nunca se le despierta desde aquí (cuesta dinero). Entonces se publican los últimos datos buenos
# con «dormido» y desde cuándo, en vez de dejar el marco sin coche.
if coche and coche.get("bateria") is None:
    prev = anterior().get("coche") or {}
    if prev.get("bateria") is not None:
        coche = {**prev, "dormido": True,
                 "dormido_desde": prev.get("dormido_desde") or datetime.now(timezone.utc).isoformat(timespec="seconds")}


def num_or_none(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


casa = {}
try:
    tc = CASA["termostato"]
    t = full(tc["entidad"])
    a = t["attributes"]
    casa["clima"] = {"nombre": tc.get("nombre", "Salón"), "temp": a.get("current_temperature"), "consigna": a.get("temperature"),
                     "modo": a.get("preset_mode") or "none", "estado": t["state"],
                     "humedad": num_or_none(state(tc["humedad"])) if tc.get("humedad") else None,
                     "pila": num_or_none(state(tc["pila"])) if tc.get("pila") else None}
except Exception:
    pass
estancias = []
for e in CASA.get("estancias", []):
    try:
        v = num_or_none(state(e["entidad"]))
        if v is not None:
            estancias.append({"nombre": e["nombre"], "temp": v})
    except Exception:
        pass
casa["estancias"] = estancias
try:
    tl = CASA["toldo"]
    c = full(tl["entidad"])
    pos = c["attributes"].get("current_position")
    # Algunos motores llegan invertidos a HA: «open 100» = recogido (compruébalo con el toldo recogido)
    sacado = None if pos is None else (100 - int(pos) if tl.get("invertido") else int(pos))
    casa["toldo"] = {"nombre": tl.get("nombre", "Toldo"), "sacado": sacado,
                     "disponible": c["state"] not in ("unavailable", "unknown")}
except Exception:
    pass


def zona(z):
    """Una zona de la climatización (climate.*, p. ej. de Airzone): temperatura, consigna, modo y qué hace ahora. Si la
    entidad aún no existe o no responde, solo el nombre (el marco la enseña «sin datos»)."""
    out = {"nombre": z.get("nombre") or "Zona"}
    try:
        c = full(z["entidad"])
    except Exception:
        return out
    if c["state"] in ("unknown", "unavailable"):
        return out
    a = c["attributes"]
    out.update({"temp": num_or_none(a.get("current_temperature")), "consigna": num_or_none(a.get("temperature")),
                "modo": c["state"], "accion": a.get("hvac_action"), "humedad": num_or_none(a.get("current_humidity"))})
    return out


def termo(t):
    """El termo eléctrico (water_heater.*, p. ej. Ariston por la integración de HACS): temperatura del agua, la que se
    pide, modo (y si es el de Boost), límites para el − / + del marco y, si están configurados, las duchas que quedan y
    si está calentando. Si la entidad aún no existe o no responde, «disponible»: false."""
    out = {"nombre": t.get("nombre") or "Termo", "disponible": False}
    try:
        w = full(t["entidad"])
    except Exception:
        return out
    if w["state"] in ("unknown", "unavailable"):
        return out
    a = w["attributes"]
    modo = a.get("operation_mode") or w["state"]
    out.update({"disponible": True, "temp": num_or_none(a.get("current_temperature")),
                "consigna": num_or_none(a.get("temperature")), "modo": modo,
                "boost": bool(t.get("modo_boost")) and modo == t.get("modo_boost"),
                "min": num_or_none(t.get("temp_min", a.get("min_temp"))), "max": num_or_none(t.get("temp_max", a.get("max_temp")))})
    for clave, conv in (("duchas", num_or_none), ("calentando", lambda v: v == "on")):
        try:
            v = state(t[clave]) if t.get(clave) else None
        except Exception:
            v = None
        out[clave] = None if v in (None, "unknown", "unavailable") else conv(v)
    return out


# Climatización por zonas y termo: con la configuración puesta, salen aunque HA aún no los tenga (el marco lo dice)
if CASA.get("zonas"):
    casa["zonas"] = [zona(z) for z in CASA["zonas"]]
if CASA.get("termo", {}).get("entidad"):
    casa["termo"] = termo(CASA["termo"])
try:
    req = urllib.request.Request(f"{HA}/api/states", headers={"Authorization": f"Bearer {TOKEN}"})
    players = [p for p in json.load(urllib.request.urlopen(req, timeout=20))
               if familia.es_altavoz(p["entity_id"])
               and p["state"] in ("playing", "paused") and p["attributes"].get("media_title")]
    if players:
        p = sorted(players, key=lambda p: p["last_changed"], reverse=True)
        p = next((x for x in p if x["state"] == "playing"), p[0])
        a = p["attributes"]
        casa["musica"] = {"altavoz": a.get("friendly_name"), "entidad": p["entity_id"], "sonando": p["state"] == "playing",
                          "titulo": a.get("media_title"), "artista": a.get("media_artist") or "", "volumen": a.get("volume_level")}
except Exception:
    pass

# Habituales: lo más pedido (al menos 2 días distintos) que no esté ya en la lista, sin lo de la lista de ignorados (bromas a Alexa, errores)
frecuentes = []
try:
    _key = familia.clave_texto
    freq = json.load(open(familia.ruta("compra_frecuentes", "compra-frecuentes.json")))
    try:
        ignorar = {_key(l) for l in open(familia.ruta("compra_ignorar", "compra-ignorar.txt"), encoding="utf-8")
                   if l.strip() and not l.startswith("#")}
    except FileNotFoundError:
        ignorar = set()
    en_lista = {_key(i["producto"]) for i in compra}
    # Los 3 más pedidos que aún no están en la lista (lo que ya está no hace falta ofrecerlo)
    top = sorted(((k, v) for k, v in freq.items() if k not in ignorar and k not in en_lista and len(v["dias"]) >= 2),
                 key=lambda kv: (-len(kv[1]["dias"]), kv[1]["nombre"]))[:3]
    frecuentes = [{"producto": v["nombre"], "veces": len(v["dias"]), "en_lista": k in en_lista} for k, v in top]
except Exception:
    pass


def seguridad_export(cfg):
    """Solo lectura: cerradura (estado de HA, batería, puerta) y alarma. Lo que no haya (null en la configuración, la
    entidad aún no existe o HA no responde) queda en None; sin «seguridad» en la configuración, None entero."""
    if not cfg:
        return None

    def leer(entidad, conv=lambda v: v):
        if not entidad:
            return None
        try:
            v = state(entidad)
        except Exception:
            return None
        return None if v in ("unknown", "unavailable") else conv(v)

    def on(v):
        return v == "on"

    out = {}
    c = cfg.get("cerradura")
    if c and c.get("entidad"):
        out["cerradura"] = {"nombre": c.get("nombre") or "Puerta", "estado": leer(c["entidad"]),
                            "bateria": leer(c.get("bateria"), numero), "bateria_critica": leer(c.get("bateria_critica"), on),
                            "cargando": leer(c.get("cargando"), on),
                            "puerta": leer(c.get("puerta"), lambda v: "abierta" if v == "on" else "cerrada")}
    a = cfg.get("alarma")
    if a and a.get("entidad"):
        out["alarma"] = {"nombre": a.get("nombre") or "Alarma", "estado": leer(a["entidad"])}
    return out or None


seguridad = seguridad_export(CFG.get("seguridad"))

data = {"items": [{"producto": i["producto"], "detalle": i["detalle"]} for i in compra], "listas": listas, "coche": coche,
        "moto": moto, "casa": casa, "frecuentes": frecuentes, "seguridad": seguridad}
try:
    old = json.load(open(OUT))
    if {k: old.get(k) for k in data} == data:
        raise SystemExit(0)
except (FileNotFoundError, ValueError):
    pass
data["actualizado"] = datetime.now(timezone.utc).isoformat(timespec="seconds")
fd, tmp = tempfile.mkstemp(dir=os.path.dirname(OUT))
with os.fdopen(fd, "w") as f:
    json.dump(data, f, ensure_ascii=False)
os.chmod(tmp, 0o644)
os.replace(tmp, OUT)
