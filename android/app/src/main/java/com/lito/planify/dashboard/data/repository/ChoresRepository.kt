package com.lito.planify.dashboard.data.repository

import com.lito.planify.dashboard.data.Api
import com.lito.planify.dashboard.data.ChoreDoneRequest
import com.lito.planify.dashboard.data.ChoreUndoRequest
import com.lito.planify.dashboard.data.Chores
import com.lito.planify.dashboard.data.RewardClaimRequest
import com.lito.planify.dashboard.data.PenaltyRequest
import com.lito.planify.dashboard.data.RewardDecisionRequest
import com.lito.planify.dashboard.data.StarSaveRequest
import com.lito.planify.dashboard.data.bodyOrThrow
import com.lito.planify.dashboard.data.cache.Cached
import com.lito.planify.dashboard.data.cache.CachedResource
import com.lito.planify.dashboard.data.cache.DiskCache
import kotlinx.coroutines.flow.StateFlow

/**
 * Tareas de casa de los niños (TaskMate en Home Assistant, a través de familia-tareas en el servidor). Los niños las
 * marcan en el marco: cada cambio devuelve el estado nuevo, que se publica enseguida. Si falla (otro ya la marcó, sin
 * red...), se vuelve a pedir el estado para que la pantalla diga la verdad.
 */
class ChoresRepository(private val api: Api, cache: DiskCache, private val url: String, private val token: String) {
    private val resource = CachedResource<Chores>(cache, "chores", Chores::class.java, MAX_AGE_MS)

    val chores: StateFlow<Cached<Chores>?> = resource.data

    fun isStale() = resource.isStale()

    suspend fun refresh(): Result<Chores> = resource.refresh { api.chores(url, token).bodyOrThrow() }

    suspend fun done(chore: String, kid: String): Result<Chores> =
        resource.refresh { api.choreDone(url + "hecha", token, ChoreDoneRequest(chore, kid)).bodyOrThrow() }
            .onFailure { refresh() }

    suspend fun undo(done: String): Result<Chores> =
        resource.refresh { api.choreUndo(url + "deshacer", token, ChoreUndoRequest(done)).bodyOrThrow() }
            .onFailure { refresh() }

    /** El niño mete una de sus estrellas sueltas en la hucha de [reward]. */
    suspend fun save(kid: String, reward: String): Result<Chores> =
        resource.refresh { api.starSave(url + "guardar", token, StarSaveRequest(kid, reward)).bodyOrThrow() }
            .onFailure { refresh() }

    /** El niño pide canjear [reward]; queda pendiente hasta que un padre lo apruebe. */
    suspend fun claim(reward: String, kid: String): Result<Chores> =
        resource.refresh { api.rewardClaim(url + "pedir", token, RewardClaimRequest(reward, kid)).bodyOrThrow() }
            .onFailure { refresh() }

    /** Un padre aprueba ([approve]) o rechaza el canje [claim] con su [pin]; un PIN malo es un HttpError 403. */
    suspend fun decide(claim: String, pin: String, approve: Boolean): Result<Chores> =
        resource.refresh {
            api.rewardDecision(url + if (approve) "aprobar" else "rechazar", token, RewardDecisionRequest(claim, pin)).bodyOrThrow()
        }

    /** Un padre penaliza a [kid] con [penalty] y su [pin]; un PIN malo es un HttpError 403. */
    suspend fun penalize(penalty: String, kid: String, pin: String): Result<Chores> =
        resource.refresh { api.penalty(url + "penalizar", token, PenaltyRequest(penalty, kid, pin)).bodyOrThrow() }

    companion object {
        const val MAX_AGE_MS = 2 * 60_000L
    }
}
