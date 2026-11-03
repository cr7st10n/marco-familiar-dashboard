package com.lito.planify.dashboard.data

import com.lito.planify.dashboard.BuildConfig
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

/** Planify (solo lectura: autenticación y listados del dashboard), Open-Meteo y los datos de la familia. */
interface Api {
    @POST("user/auth")
    suspend fun auth(@Body request: AuthRequest): Response<UserResponse>

    /**
     * `start_date`/`end_date`: hora local expresada como segundos epoch en UTC, igual que la app. Sin `end_date`
     * (null) no hay límite: todos los eventos desde `start_date`.
     */
    @GET("event")
    suspend fun events(
        @Header("Authorization") auth: String,
        @Query("start_date") start: Long,
        @Query("end_date") end: Long?,
        @Query("routine") routine: Boolean,
        @Query("timezone") timezone: String,
        @Query("dashboard") dashboard: Boolean = true
    ): Response<List<Event>>

    @GET("task")
    suspend fun tasks(@Header("Authorization") auth: String, @Query("dashboard") dashboard: Boolean = true): Response<List<Task>>

    @GET
    suspend fun forecast(@Url url: String): Response<Forecast>

    @GET
    suspend fun geocode(@Url url: String): Response<GeoResponse>

    @GET
    suspend fun ree(@Url url: String): Response<ReeResponse>

    @GET
    suspend fun shopping(@Url url: String): Response<ShoppingList>

    /** Tareas de los niños: [url] es la base («…/tareas/»); el token del marco va en la cabecera X-Marco. */
    @GET
    suspend fun chores(@Url url: String, @Header("X-Marco") token: String): Response<Chores>

    @POST
    suspend fun choreDone(@Url url: String, @Header("X-Marco") token: String, @Body request: ChoreDoneRequest): Response<Chores>

    @POST
    suspend fun choreUndo(@Url url: String, @Header("X-Marco") token: String, @Body request: ChoreUndoRequest): Response<Chores>

    @POST
    suspend fun rewardClaim(@Url url: String, @Header("X-Marco") token: String, @Body request: RewardClaimRequest): Response<Chores>

    /** Guardar una estrella en la hucha de una recompensa: «…/tareas/guardar». */
    @POST
    suspend fun starSave(@Url url: String, @Header("X-Marco") token: String, @Body request: StarSaveRequest): Response<Chores>

    /** Quitar de la compra (Bring! lo da por comprado): «…/tareas/compra/quitar». */
    @POST
    suspend fun shoppingRemove(@Url url: String, @Header("X-Marco") token: String, @Body request: ShoppingRemoveRequest): Response<okhttp3.ResponseBody>

    /** Añadir a la compra (un habitual tocado en el marco): «…/tareas/compra/anadir». */
    @POST
    suspend fun shoppingAdd(@Url url: String, @Header("X-Marco") token: String, @Body request: ShoppingRemoveRequest): Response<okhttp3.ResponseBody>

    /** Órdenes a la casa (termostato, toldo, música): «…/tareas/casa/<qué>», con sus datos. */
    @POST
    suspend fun homeAction(@Url url: String, @Header("X-Marco") token: String, @Body body: Map<String, @JvmSuppressWildcards Any>): Response<okhttp3.ResponseBody>

    /** Aplicar una penalización con el PIN de padres: «…/tareas/penalizar». */
    @POST
    suspend fun penalty(@Url url: String, @Header("X-Marco") token: String, @Body request: PenaltyRequest): Response<Chores>

    /** «…/aprobar» o «…/rechazar». */
    @POST
    suspend fun rewardDecision(@Url url: String, @Header("X-Marco") token: String, @Body request: RewardDecisionRequest): Response<Chores>

    companion object {
        val client: Api by lazy {
            val http = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            Retrofit.Builder()
                // Sin Planify (solo calendarios iCal) no hay URL: Retrofit exige una, y solo se usan URLs completas
                .baseUrl(BuildConfig.BASE_URL.ifEmpty { "https://localhost/" })
                .client(http)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(Api::class.java)
        }

        fun forecastUrl(location: Location) = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${location.latitude}&longitude=${location.longitude}&timezone=auto&past_days=1&forecast_days=14" +
            "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m,wind_direction_10m,is_day" +
            "&hourly=temperature_2m,precipitation_probability,precipitation,weather_code,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum,wind_speed_10m_max,sunrise,sunset"

        /** PVPC por horas (y el mercado spot, que no se usa) de [from] a [to], ambos incluidos. Sin clave. */
        fun reeUrl(from: java.time.LocalDate, to: java.time.LocalDate) =
            "https://apidatos.ree.es/es/datos/mercados/precios-mercados-tiempo-real" +
                "?start_date=${from}T00:00&end_date=${to}T23:59&time_trunc=hour"

        fun geocodeUrl(query: String) = "https://geocoding-api.open-meteo.com/v1/search?count=8&language=es&format=json&name=" +
            java.net.URLEncoder.encode(query, "UTF-8")
    }
}
