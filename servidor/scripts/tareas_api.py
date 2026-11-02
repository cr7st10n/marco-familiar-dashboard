#!/usr/bin/env python3
"""Tareas y recompensas de los niños (TaskMate en Home Assistant) para el marco.

En el servidor: servicio familia-tareas (systemd, usuario dinámico) en 127.0.0.1:<tareas.puerto> (8124 en el
ejemplo), detrás de nginx en una ruta secreta. Además de la ruta, cada petición lleva la cabecera X-Marco con el token
del marco. Home Assistant no se expone: solo se permiten estas operaciones, las de tareas siempre sobre las de hoy.

  GET  /                                  → {"fecha", "ninos", "tareas", "recompensas", "pedidas"}
  POST /hecha     {"tarea", "nino"}       → la marca como hecha por ese niño; devuelve el estado nuevo
  POST /deshacer  {"hecha"}               → deshace una de hoy (quita las estrellas); devuelve el estado nuevo
  POST /guardar   {"nino", "recompensa", "hecha"?} → guarda 1 estrella sin guardar en la hucha de esa recompensa
                                          («hecha»: la tarea de la que viene, para sacarla si se deshace)
  POST /pedir     {"recompensa", "nino"}  → el niño pide el canje (queda pendiente) y se avisa en Todoist; solo con la
                                          hucha de esa recompensa llena
  POST /aprobar   {"pedida", "pin"}       → un padre lo aprueba con el PIN (se descuentan las estrellas)
  POST /penalizar {"penalizacion", "nino", "pin"} → un padre aplica una penalización de TaskMate con el PIN
  POST /rechazar  {"pedida", "pin"}       → un padre lo rechaza con el PIN (no se descuenta nada)
  POST /compra/quitar {"producto"}        → lo marca comprado en Bring! (sigue en «recientes» por si fue un error);
                                            compra_sync.py lo quita de Alexa al minuto
  POST /compra/anadir {"producto"}        → lo añade a Bring! (o lo vuelve a pedir si estaba comprado)
  POST /casa/consigna {"grados"}          → consigna del termostato (por defecto 15–25 °C, de 0,5 en 0,5)
  POST /casa/modo     {"modo"}            → uno de «casa.termostato.modos» (p. ej. home / away / night / freeze)
  POST /casa/toldo    {"accion"}          → sacar / recoger / parar (con «invertido», al revés en HA)
  POST /casa/musica   {"accion", "altavoz"} → play / pause / next / previous en un Echo, o «volumen» con "nivel" (0–1)

Huchas: TaskMate solo lleva un monedero de estrellas por niño. Para que cada niño decida para qué recompensa ahorra,
este servicio guarda aparte (huchas.json en STATE_DIRECTORY o, sin systemd, en «rutas.base») cuántas de sus estrellas
ha metido en la hucha de cada recompensa. Las que no están en ninguna son «libres» (por guardar). Si TaskMate le quita
estrellas por otro lado (un canje aprobado fuera del marco, un ajuste de los padres), salen de las huchas que menos
llevan.

El PIN de padres lo comprueba este servicio, no el marco: 5 fallos seguidos (por defecto) lo bloquean 15 minutos.
Credenciales por LoadCredential de systemd: ha.token (Home Assistant), marco.token (el del marco) y padres.pin; y la
configuración, familia.json (o la ruta de FAMILIA_CONFIG). Sin systemd, se buscan en «rutas.base».

De config/familia.json: «home_assistant», «casa» (termostato, toldo, música), «ninos», «otros_colores» y «tareas»
(puerto, intentos y bloqueo del PIN, emojis).
"""
from __future__ import annotations

import hmac
import json
import os
import time
import urllib.error
import urllib.request
import sys
import threading
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from zoneinfo import ZoneInfo

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import familia  # noqa: E402

CFG = familia.config()
_CASA, _TAREAS = CFG.get("casa", {}), CFG.get("tareas", {})
HA = familia.ha_url()
PORT = int(_TAREAS.get("puerto", 8124))
TZ = ZoneInfo(familia.zona())
CRED = os.environ.get("CREDENTIALS_DIRECTORY") or familia.base()
HA_TOKEN = familia.leer(os.path.join(CRED, "ha.token"))
MARCO_TOKEN = familia.leer(os.path.join(CRED, "marco.token"))
PADRES_PIN = familia.leer(os.path.join(CRED, "padres.pin"))
TASKS = familia.ha().get("tareas_todoist", "")  # avisos a los padres (lista de Todoist); vacío = sin avisos
BRING = familia.entidad("compra_bring")  # lista de la compra (Bring!)
CLIMA = _CASA.get("termostato", {}).get("entidad", "")
CONSIGNA_MIN = float(_CASA.get("termostato", {}).get("consigna_min", 15))
CONSIGNA_MAX = float(_CASA.get("termostato", {}).get("consigna_max", 25))
MODOS = set(_CASA.get("termostato", {}).get("modos", ["home", "away", "night", "freeze"]))
TOLDO = _CASA.get("toldo", {}).get("entidad", "")
# Si el toldo llega al revés en HA («open 100» = recogido), sacar es cerrar y recoger es abrir
TOLDO_ACCIONES = ({"sacar": "close_cover", "recoger": "open_cover", "parar": "stop_cover"}
                  if _CASA.get("toldo", {}).get("invertido") else
                  {"sacar": "open_cover", "recoger": "close_cover", "parar": "stop_cover"})
MUSICA_ACCIONES = {"play": "media_play", "pause": "media_pause", "next": "media_next_track", "previous": "media_previous_track"}
PIN_FALLOS, PIN_BLOQUEO = int(_TAREAS.get("pin_fallos", 5)), int(_TAREAS.get("pin_bloqueo_min", 15)) * 60
_pin = {"fallos": 0, "hasta": 0.0}
HUCHAS = os.path.join(os.environ.get("STATE_DIRECTORY") or familia.base(), "huchas.json")
_huchas_lock = threading.Lock()
# Colores de cada niño en el marco (TaskMate no los expone en los sensores)
COLORES = {n["nombre"]: n["color"] for n in CFG.get("ninos", []) if n.get("color")}
OTROS_COLORES = CFG.get("otros_colores") or ["#8E24AA", "#F4511E", "#00897B", "#FDD835"]
# El marco no tiene los iconos mdi de HA: emoji según el icono de la tarea (o el que lleve el nombre al principio).
# Los de «tareas.emojis» se añaden a estos o los sustituyen.
EMOJIS = {"mdi:cat": "🐈", "mdi:broom": "🧹", "mdi:dog": "🐕", "mdi:trash-can": "🗑️", "mdi:bed": "🛏️",
          "mdi:silverware-fork-knife": "🍽️", "mdi:washing-machine": "🧺", "mdi:sprout": "🪴", "mdi:book-open-variant": "📖",
          "mdi:tooth": "🪥", "mdi:tshirt-crew": "👕", "mdi:dishwasher": "🍽️", "mdi:paw": "🐾",
          "mdi:cards": "🃏", "mdi:movie-open": "🍿", "mdi:pizza": "🍕", "mdi:gamepad-variant": "🎮", "mdi:ice-cream": "🍦",
          "mdi:gift": "🎁", "mdi:soccer": "⚽", "mdi:television": "📺", "mdi:candy": "🍬",
          "mdi:desk": "📚",
          "mdi:shower-head": "🚿", "mdi:shower": "🚿", "mdi:bag-personal": "🎒", "mdi:bag-personal-outline": "🎒",
          "mdi:backpack": "🎒", "mdi:chef-hat": "🍳", "mdi:food": "🍔", "mdi:food-apple": "🍎",
          "mdi:toothbrush": "🪥", "mdi:hanger": "👕", "mdi:bicycle": "🚲", "mdi:bike": "🚲", "mdi:music": "🎵",
          "mdi:swim": "🏊", "mdi:basketball": "🏀", "mdi:cake": "🍰", "mdi:cookie": "🍪", "mdi:popcorn": "🍿",
          "mdi:alert-circle-outline": "⚠️", "mdi:alert-circle": "⚠️", "mdi:alert": "⚠️", "mdi:thumb-down": "👎",
          "mdi:emoticon-angry": "😠", "mdi:emoticon-sad": "😢", "mdi:cancel": "🚫", "mdi:volume-high": "📢",
          "mdi:star": "⭐", "mdi:flower": "🌸", "mdi:pencil": "✏️", "mdi:baby-face": "👶", "mdi:account-group": "👪",
          **{k: v for k, v in _TAREAS.get("emojis", {}).items() if not k.startswith("_")}}


def ha(path: str, body: dict | None = None):
    req = urllib.request.Request(HA + path, data=json.dumps(body).encode() if body is not None else None,
                                 method="POST" if body is not None else "GET",
                                 headers={"Authorization": f"Bearer {HA_TOKEN}", "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.loads(r.read() or b"null")


def attrs(entity: str) -> dict:
    return ha(f"/api/states/{entity}")["attributes"]


def emoji(chore: dict, default: str = "✅") -> str:
    name = chore.get("name", "")
    if name and not name[0].isalnum():
        return name.split(" ", 1)[0]
    return EMOJIS.get(chore.get("icon", ""), default)


def nombre(chore: dict) -> str:
    """El nombre sin el emoji del principio, si lo lleva (ya va aparte)."""
    name = chore.get("name", "")
    return name.split(" ", 1)[1] if name and not name[0].isalnum() and " " in name else name


def fila(c: dict) -> dict:
    return {"id": c["id"], "nombre": nombre(c), "emoji": emoji(c), "estrellas": c.get("points", 0), "para": [], "hechas": []}


def estado() -> dict:
    overview = attrs("sensor.taskmate_overview")
    chores_attrs = attrs("sensor.taskmate_chores")
    board = attrs("sensor.taskmate_chore_board")["chore_board"]["board"]
    chores = {c["id"]: c for c in chores_attrs.get("chores", [])}
    ninos = []
    for i, c in enumerate(overview.get("children", [])):
        ninos.append({"id": c["id"], "nombre": c["name"],
                      "color": COLORES.get(c["name"], OTROS_COLORES[i % len(OTROS_COLORES)]),
                      "estrellas": c.get("points", 0), "semana": c.get("week_points_earned", 0),
                      # Lo que puede gastar: sus estrellas menos lo apartado en canjes pendientes
                      "disponibles": c.get("spendable_balance", c.get("points", 0))})
    nombres = {n["id"]: n["nombre"] for n in ninos}
    # Una fila por tarea de hoy: para quién está pendiente y quién la ha hecho
    tareas: dict[str, dict] = {}
    for entry in board.get("children", []):
        for item in entry.get("chores", []):
            c = chores.get(item["chore_id"])
            if not c:
                continue
            t = tareas.setdefault(c["id"], fila(c))
            if item["status"] in ("todo", "missed"):
                t["para"].append(entry["child_id"])
    for done in chores_attrs.get("todays_completions", []):
        t = tareas.get(done["chore_id"])
        if t is None:
            c = chores.get(done["chore_id"], {"id": done["chore_id"], "name": done.get("chore_name", "")})
            t = tareas.setdefault(c["id"], fila(c))
        hora = datetime.fromisoformat(done["completed_at"]).astimezone(TZ).strftime("%H:%M")
        t["hechas"].append({"id": done["completion_id"], "nino": done["child_id"],
                            "nombre": nombres.get(done["child_id"], done.get("child_name", "")), "hora": hora,
                            "aprobada": bool(done.get("approved"))})
    orden = [cid for cid in chores if cid in tareas]
    rewards_attrs = attrs("sensor.taskmate_rewards")
    recompensas = [{"id": r["id"], "nombre": nombre(r), "emoji": emoji(r, "🎁"),
                    "coste": r.get("cost", 0), "costes": r.get("calculated_costs") or {},
                    "para": r.get("assigned_to") or [n["id"] for n in ninos],
                    "disponible": bool(r.get("is_available", True)) and not r.get("is_sold_out")}
                   for r in rewards_attrs.get("rewards", [])]
    pedidas = []
    for p in rewards_attrs.get("pending_reward_claims", []):
        hora = datetime.fromisoformat(p["claimed_at"]).astimezone(TZ)
        pedidas.append({"id": p["claim_id"], "recompensa": p["reward_id"], "nino": p["child_id"],
                        "nombre": nombres.get(p["child_id"], p.get("child_name", "")), "titulo": p.get("reward_name", ""),
                        "emoji": EMOJIS.get(p.get("reward_icon", ""), "🎁"), "coste": p.get("cost", 0),
                        "cuando": hora.strftime("%d/%m %H:%M")})
    penalizaciones = [{"id": p["id"], "nombre": nombre(p), "emoji": emoji(p, "⚠️"), "estrellas": p.get("points", 0),
                       "para": p.get("assigned_to") or [n["id"] for n in ninos]}
                      for p in attrs("sensor.taskmate_incentives").get("penalties", [])]
    return repartir({"fecha": board.get("date"), "ninos": ninos, "tareas": [tareas[cid] for cid in orden],
                     "recompensas": recompensas, "pedidas": pedidas, "penalizaciones": penalizaciones})


def huchas_leer() -> dict:
    """{"huchas": {niño: {recompensa: estrellas}}, "tareas": {hecha: [recompensa, ...]}} (vacío si aún no hay)."""
    try:
        with open(HUCHAS, encoding="utf-8") as f:
            d = json.load(f)
    except FileNotFoundError:
        d = {}
    d.setdefault("huchas", {})
    d.setdefault("tareas", {})
    return d


def huchas_escribir(d: dict) -> None:
    tmp = HUCHAS + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(d, f, ensure_ascii=False, indent=1)
    os.replace(tmp, HUCHAS)


def coste(r: dict, nino: str) -> int:
    return int(r["costes"].get(nino, r["coste"]))


def repartir(e: dict) -> dict:
    """Añade a cada niño de [e] sus «huchas» y sus estrellas «libres», y deja las huchas cuadradas con TaskMate."""
    with _huchas_lock:
        d = huchas_leer()
        antes = json.dumps(d, sort_keys=True)
        recompensas = {r["id"]: r for r in e["recompensas"]}
        for n in e["ninos"]:
            h = d["huchas"].get(n["id"], {})
            # Recompensas que ya no existen o no son suyas: sus estrellas vuelven a estar libres
            h = {rid: min(int(v), coste(recompensas[rid], n["id"])) for rid, v in h.items()
                 if rid in recompensas and n["id"] in recompensas[rid]["para"] and int(v) > 0}
            # Si tiene menos estrellas que lo guardado, se sacan de las huchas que menos llevan
            exceso = sum(h.values()) - max(0, n["estrellas"])
            while exceso > 0 and h:
                rid = min(h, key=lambda k: (h[k], k))
                quita = min(h[rid], exceso)
                h[rid] -= quita
                exceso -= quita
                if not h[rid]:
                    del h[rid]
            if h:
                d["huchas"][n["id"]] = h
            else:
                d["huchas"].pop(n["id"], None)
            n["huchas"] = h
            n["libres"] = max(0, n["estrellas"] - sum(h.values()))
        # Solo hace falta recordar de dónde vino cada estrella mientras la tarea se pueda deshacer (hoy)
        hoy = {h["id"] for t in e["tareas"] for h in t["hechas"]}
        d["tareas"] = {k: v for k, v in d["tareas"].items() if k in hoy}
        if json.dumps(d, sort_keys=True) != antes:
            huchas_escribir(d)
    return e


def huchas_cambiar(nino: str, rid: str, delta: int) -> None:
    with _huchas_lock:
        d = huchas_leer()
        h = d["huchas"].setdefault(nino, {})
        h[rid] = max(0, h.get(rid, 0) + delta)
        huchas_escribir(d)


def avisar(titulo: str, detalle: str = "") -> None:
    """Tarea en la lista de Todoist (vía Home Assistant): los padres la ven en el móvil. Si falla, el canje sigue."""
    if not TASKS:
        return
    try:
        ha("/api/services/todo/add_item", {"entity_id": TASKS, "item": titulo, **({"description": detalle} if detalle else {})})
    except Exception:  # noqa: BLE001
        pass


def pin_ok(pin: str) -> str | None:
    """None si el PIN vale; si no, el motivo. Tras PIN_FALLOS fallos seguidos, bloqueado PIN_BLOQUEO segundos."""
    if time.time() < _pin["hasta"]:
        return f"PIN bloqueado: prueba dentro de {int((_pin['hasta'] - time.time()) // 60) + 1} min"
    if hmac.compare_digest(pin, PADRES_PIN):
        _pin["fallos"] = 0
        return None
    _pin["fallos"] += 1
    if _pin["fallos"] >= PIN_FALLOS:
        _pin.update(fallos=0, hasta=time.time() + PIN_BLOQUEO)
        return f"PIN incorrecto: bloqueado {PIN_BLOQUEO // 60} min"
    return "PIN incorrecto"


def mensaje(e: Exception) -> str:
    """El texto del error de Home Assistant (p. ej. «Not enough points»), sin el resto de la respuesta."""
    if isinstance(e, urllib.error.HTTPError):
        try:
            return json.loads(e.read() or b"{}").get("message") or str(e)
        except Exception:  # noqa: BLE001
            return str(e)
    return str(e)


def esperar(cond, segundos: float = 6) -> dict:
    """TaskMate actualiza los sensores un instante después del servicio: espera a verlo para devolverlo ya cambiado."""
    fin = time.time() + segundos
    while True:
        e = estado()
        if cond(e) or time.time() > fin:
            return e
        time.sleep(0.4)


class Handler(BaseHTTPRequestHandler):
    server_version = "familia-tareas"

    def log_message(self, fmt, *args):  # sin registro de accesos (la ruta es secreta)
        pass

    def reply(self, code: int, data) -> None:
        body = json.dumps(data, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def authorized(self) -> bool:
        if hmac.compare_digest(self.headers.get("X-Marco", ""), MARCO_TOKEN):
            return True
        self.reply(403, {"error": "no autorizado"})
        return False

    def quitar_compra(self, producto: str) -> None:
        """Marca [producto] como comprado en Bring! (por su nombre, sin distinguir mayúsculas)."""
        items = ha("/api/services/todo/get_items?return_response", {"entity_id": BRING, "status": "needs_action"})
        found = next((i for i in items["service_response"][BRING]["items"]
                      if i["summary"].strip().lower() == producto.strip().lower()), None)
        if found is None:
            return self.reply(409, {"error": "ya no está en la lista"})
        ha("/api/services/todo/update_item", {"entity_id": BRING, "item": found["uid"], "status": "completed"})
        self.reply(200, {"quitado": found["summary"]})

    def casa(self, que: str, body: dict) -> None:
        """Órdenes a la casa: solo estas, con valores comprobados antes de llamar a Home Assistant."""
        if que == "consigna":
            try:
                grados = float(body.get("grados"))
            except (TypeError, ValueError):
                return self.reply(400, {"error": "grados no válidos"})
            if not CLIMA:
                return self.reply(404, {"error": "no hay termostato"})
            if not CONSIGNA_MIN <= grados <= CONSIGNA_MAX or (grados * 2) % 1:
                return self.reply(400, {"error": f"consigna entre {CONSIGNA_MIN:g} y {CONSIGNA_MAX:g}, de medio en medio grado"})
            ha("/api/services/climate/set_temperature", {"entity_id": CLIMA, "temperature": grados})
        elif que == "modo":
            modo = str(body.get("modo", ""))
            if not CLIMA:
                return self.reply(404, {"error": "no hay termostato"})
            if modo not in MODOS:
                return self.reply(400, {"error": "modo no válido"})
            ha("/api/services/climate/set_preset_mode", {"entity_id": CLIMA, "preset_mode": modo})
        elif que == "toldo":
            servicio = TOLDO_ACCIONES.get(str(body.get("accion", "")))
            if not TOLDO:
                return self.reply(404, {"error": "no hay toldo"})
            if not servicio:
                return self.reply(400, {"error": "acción no válida"})
            ha(f"/api/services/cover/{servicio}", {"entity_id": TOLDO})
        elif que == "musica":
            accion, altavoz = str(body.get("accion", "")), str(body.get("altavoz", ""))
            # Solo los reproductores de Alexa Media Player (los Echo) de la configuración, nunca otra entidad
            if not familia.es_altavoz(altavoz):
                return self.reply(400, {"error": "altavoz no válido"})
            if accion in MUSICA_ACCIONES:
                ha(f"/api/services/media_player/{MUSICA_ACCIONES[accion]}", {"entity_id": altavoz})
            elif accion == "volumen":
                # El marco manda el volumen final (0–1) a partir del que ve: el Echo tarda en confirmar el anterior
                try:
                    nivel = float(body.get("nivel"))
                except (TypeError, ValueError):
                    return self.reply(400, {"error": "nivel no válido"})
                ha("/api/services/media_player/volume_set", {"entity_id": altavoz, "volume_level": round(min(1.0, max(0.0, nivel)), 2)})
            else:
                return self.reply(400, {"error": "acción no válida"})
        else:
            return self.reply(404, {"error": "no existe"})
        self.reply(200, {"ok": True})

    def anadir_compra(self, producto: str) -> None:
        """Añade [producto] a Bring! (un habitual tocado en el marco); si estaba comprado, lo vuelve a pedir."""
        producto = producto.strip()[:80]
        if not producto:
            return self.reply(400, {"error": "falta el producto"})
        items = ha("/api/services/todo/get_items?return_response", {"entity_id": BRING})["service_response"][BRING]["items"]
        mismo = [i for i in items if i["summary"].strip().lower() == producto.lower()]
        if any(i["status"] == "needs_action" for i in mismo):
            return self.reply(200, {"anadido": producto, "ya_estaba": True})
        if mismo:
            ha("/api/services/todo/update_item", {"entity_id": BRING, "item": mismo[0]["uid"], "status": "needs_action"})
        else:
            ha("/api/services/todo/add_item", {"entity_id": BRING, "item": producto})
        self.reply(200, {"anadido": producto})

    def do_GET(self):
        if not self.authorized():
            return
        if self.path.rstrip("/") != "":
            return self.reply(404, {"error": "no existe"})
        try:
            self.reply(200, estado())
        except Exception as e:  # noqa: BLE001
            self.reply(502, {"error": f"Home Assistant: {e}"})

    def do_POST(self):
        if not self.authorized():
            return
        try:
            n = int(self.headers.get("Content-Length", "0"))
            if n > 2048:
                return self.reply(413, {"error": "demasiado grande"})
            body = json.loads(self.rfile.read(n) or b"{}")
            if self.path == "/compra/quitar":
                return self.quitar_compra(str(body.get("producto", "")))
            if self.path == "/compra/anadir":
                return self.anadir_compra(str(body.get("producto", "")))
            if self.path.startswith("/casa/"):
                return self.casa(self.path.removeprefix("/casa/"), body)
            actual = estado()
            if self.path == "/hecha":
                tarea, nino = str(body.get("tarea", "")), str(body.get("nino", ""))
                t = next((t for t in actual["tareas"] if t["id"] == tarea), None)
                if t is None or nino not in t["para"]:
                    return self.reply(409, {"error": "esa tarea ya está hecha o no es de ese niño", "estado": actual})
                antes = len(t["hechas"])
                ha("/api/services/taskmate/complete_chore", {"chore_id": tarea, "child_id": nino})
                return self.reply(200, esperar(lambda e: any(x["id"] == tarea and len(x["hechas"]) > antes for x in e["tareas"])))
            if self.path == "/deshacer":
                hecha = str(body.get("hecha", ""))
                if not any(h["id"] == hecha for t in actual["tareas"] for h in t["hechas"]):
                    return self.reply(409, {"error": "no es una tarea hecha hoy", "estado": actual})
                nino = next(h["nino"] for t in actual["tareas"] for h in t["hechas"] if h["id"] == hecha)
                with _huchas_lock:
                    d = huchas_leer()
                    h = d["huchas"].get(nino, {})
                    for rid in d["tareas"].pop(hecha, []):
                        if h.get(rid, 0) > 0:
                            h[rid] -= 1
                    huchas_escribir(d)
                ha("/api/services/taskmate/undo_chore_approval", {"completion_id": hecha})
                ha("/api/services/taskmate/reject_chore", {"completion_id": hecha, "reason": "Deshecho en el marco"})
                return self.reply(200, esperar(lambda e: not any(h["id"] == hecha for t in e["tareas"] for h in t["hechas"])))
            if self.path == "/guardar":
                rec, nino, hecha = str(body.get("recompensa", "")), str(body.get("nino", "")), str(body.get("hecha") or "")
                r = next((r for r in actual["recompensas"] if r["id"] == rec), None)
                n = next((n for n in actual["ninos"] if n["id"] == nino), None)
                if r is None or n is None or nino not in r["para"] or not r["disponible"]:
                    return self.reply(409, {"error": "esa recompensa no es de ese niño", "estado": actual})
                with _huchas_lock:
                    d = huchas_leer()
                    h = d["huchas"].setdefault(nino, {})
                    if n["estrellas"] - sum(h.values()) < 1:
                        return self.reply(409, {"error": "no le quedan estrellas por guardar", "estado": actual})
                    if h.get(rec, 0) >= coste(r, nino):
                        return self.reply(409, {"error": "esa hucha ya está llena", "estado": actual})
                    h[rec] = h.get(rec, 0) + 1
                    if hecha and any(x["id"] == hecha and x["nino"] == nino for t in actual["tareas"] for x in t["hechas"]):
                        d["tareas"].setdefault(hecha, []).append(rec)
                    huchas_escribir(d)
                return self.reply(200, estado())
            if self.path == "/pedir":
                rec, nino = str(body.get("recompensa", "")), str(body.get("nino", ""))
                r = next((r for r in actual["recompensas"] if r["id"] == rec), None)
                n = next((n for n in actual["ninos"] if n["id"] == nino), None)
                if r is None or n is None or nino not in r["para"]:
                    return self.reply(409, {"error": "esa recompensa no es de ese niño", "estado": actual})
                if n["huchas"].get(rec, 0) < coste(r, nino):
                    return self.reply(409, {"error": "aún no está llena la hucha de esa recompensa", "estado": actual})
                antes = len(actual["pedidas"])
                try:
                    ha("/api/services/taskmate/claim_reward", {"reward_id": rec, "child_id": nino})
                except Exception as e:  # noqa: BLE001
                    return self.reply(409, {"error": mensaje(e), "estado": actual})
                avisar(f"🎁 {n['nombre']} pide «{r['nombre']}» ({r['costes'].get(nino, r['coste'])} ⭐)",
                       "Apruébalo o recházalo en el marco (pestaña Tareas) con el PIN de padres.")
                return self.reply(200, esperar(lambda e: len(e["pedidas"]) > antes))
            if self.path == "/penalizar":
                pen, nino = str(body.get("penalizacion", "")), str(body.get("nino", ""))
                p = next((p for p in actual["penalizaciones"] if p["id"] == pen), None)
                n = next((n for n in actual["ninos"] if n["id"] == nino), None)
                if p is None or n is None or nino not in p["para"]:
                    return self.reply(409, {"error": "esa penalización no es de ese niño", "estado": actual})
                motivo = pin_ok(str(body.get("pin", "")))
                if motivo:
                    return self.reply(403, {"error": motivo})
                ha("/api/services/taskmate/apply_penalty", {"penalty_id": pen, "child_id": nino})
                antes = n["estrellas"]
                return self.reply(200, esperar(lambda e: any(x["id"] == nino and x["estrellas"] != antes for x in e["ninos"]), 4))
            if self.path in ("/aprobar", "/rechazar"):
                pedida = str(body.get("pedida", ""))
                if not any(p["id"] == pedida for p in actual["pedidas"]):
                    return self.reply(409, {"error": "ese canje ya no está pendiente", "estado": actual})
                motivo = pin_ok(str(body.get("pin", "")))
                if motivo:
                    return self.reply(403, {"error": motivo})
                if self.path == "/aprobar":
                    # Las estrellas del canje salen de su hucha (antes de que TaskMate las descuente, para que no se
                    # saquen de otra)
                    p = next(p for p in actual["pedidas"] if p["id"] == pedida)
                    huchas_cambiar(p["nino"], p["recompensa"], -int(p["coste"]))
                    try:
                        ha("/api/services/taskmate/approve_reward", {"claim_id": pedida})
                    except Exception:
                        huchas_cambiar(p["nino"], p["recompensa"], int(p["coste"]))
                        raise
                else:
                    ha("/api/services/taskmate/reject_reward", {"claim_id": pedida, "reason": "Rechazado en el marco"})
                return self.reply(200, esperar(lambda e: not any(p["id"] == pedida for p in e["pedidas"])))
            self.reply(404, {"error": "no existe"})
        except Exception as e:  # noqa: BLE001
            self.reply(502, {"error": f"Home Assistant: {e}"})


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
