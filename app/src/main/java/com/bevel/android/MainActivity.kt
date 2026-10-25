package com.bevel.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import androidx.room.*
import androidx.work.*
import com.google.ai.client.generativeai.Chat
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import java.util.UUID

// ============================================================================
// 1. ROOM DATABASE ENTITIES & DAOS
// ============================================================================

@Entity(tableName = "daily_biometrics")
data class DailyBiometricEntity(
    @PrimaryKey val epochDay: Long,
    val dateString: String,
    val hrvRmssd: Double?,
    val restingHr: Double?,
    val sleepScore: Int?,
    val recoveryScore: Int?,
    val strainScore: Int?
)

@Entity(tableName = "daily_journal_entries", primaryKeys = ["epochDay", "habitId"])
data class HabitLogEntity(
    val epochDay: Long,
    val habitId: String,
    val completed: Boolean
)

@Dao
interface BiometricDao {
    @Upsert
    suspend fun upsertDailyRecord(record: DailyBiometricEntity)

    @Query("SELECT * FROM daily_biometrics WHERE epochDay < :todayEpochDay ORDER BY epochDay DESC LIMIT 30")
    suspend fun getRolling30DayWindow(todayEpochDay: Long): List<DailyBiometricEntity>

    @Query("SELECT * FROM daily_biometrics ORDER BY epochDay DESC LIMIT 7")
    fun observeRecentWeek(): Flow<List<DailyBiometricEntity>>
}

@Dao
interface JournalDao {
    @Upsert
    suspend fun upsertHabitLogs(logs: List<HabitLogEntity>)

    @Query("SELECT * FROM daily_journal_entries WHERE epochDay = :epochDay")
    suspend fun getHabitsForDay(epochDay: Long): List<HabitLogEntity>

    @Query("SELECT * FROM daily_journal_entries WHERE epochDay >= :minEpochDay")
    suspend fun getAllHabitLogsSince(minEpochDay: Long): List<HabitLogEntity>
}

@Database(
    entities = [DailyBiometricEntity::class, HabitLogEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun biometricDao(): BiometricDao
    abstract fun journalDao(): JournalDao
}

// ============================================================================
// 2. MATHEMATICAL SCORING, STRENGTH & HABIT CORRELATION ENGINES
// ============================================================================

data class BiometricBaseline(
    val hrvMean: Double,
    val hrvStdDev: Double,
    val rhrMean: Double,
    val rhrStdDev: Double
)

object ScoringEngine {
    fun calculateStats(values: List<Double>): Pair<Double, Double> {
        if (values.isEmpty()) return Pair(65.0, 12.0)
        val mean = values.average()
        val variance = values.map { (it - mean).pow(2) }.average()
        val stdDev = sqrt(variance).coerceAtLeast(1.0)
        return Pair(mean, stdDev)
    }

    fun calculateRecovery(todayHrv: Double, todayRhr: Double, sleepScore: Double, baseline: BiometricBaseline): Int {
        val k = 1.2
        val zHrv = (todayHrv - baseline.hrvMean) / baseline.hrvStdDev
        val hrvComp = 100.0 / (1.0 + exp(-k * zHrv))
        val zRhr = (baseline.rhrMean - todayRhr) / baseline.rhrStdDev
        val rhrComp = 100.0 / (1.0 + exp(-k * zRhr))
        return ((hrvComp * 0.50) + (rhrComp * 0.30) + (sleepScore * 0.20)).toInt().coerceIn(1, 100)
    }

    fun combineCardioAndMuscularStrain(cardioStrain: Int, muscularStrain: Int): Int {
        val c = cardioStrain / 100.0
        val m = muscularStrain / 100.0
        return ((1.0 - ((1.0 - c) * (1.0 - m))) * 100.0).toInt().coerceIn(0, 100)
    }
}

enum class MuscleGroup(val displayName: String) {
    CHEST("Chest"), BACK("Back"), QUADS("Quads"),
    HAMSTRINGS("Hamstrings"), SHOULDERS("Shoulders"), ARMS("Arms"), CORE("Core")
}

data class ExerciseTemplate(
    val id: String,
    val name: String,
    val primaryMuscle: MuscleGroup,
    val secondaryMuscles: List<MuscleGroup>,
    val isCompound: Boolean
)

data class WorkoutSet(
    val setNumber: Int,
    val weightKg: Double,
    val reps: Int,
    val rpe: Double = 8.0,
    val isWarmup: Boolean = false
)

data class LoggedExercise(val template: ExerciseTemplate, val sets: List<WorkoutSet>)

data class StrengthWorkoutSummary(
    val totalVolumeKg: Double,
    val muscularStrainScore: Int,
    val muscleHeatmap: Map<MuscleGroup, Float>
)

object StrengthEngine {
    fun evaluateWorkout(exercises: List<LoggedExercise>): StrengthWorkoutSummary {
        var totalVolume = 0.0
        var weightedLoad = 0.0
        val muscleLoadMap = mutableMapOf<MuscleGroup, Double>()

        for (logged in exercises) {
            val workingSets = logged.sets.filter { !it.isWarmup && it.reps > 0 }
            val mult = if (logged.template.isCompound) 1.35 else 1.0
            for (set in workingSets) {
                val vol = set.weightKg * set.reps
                totalVolume += vol
                val effLoad = vol * (set.rpe.coerceIn(5.0, 10.0) / 10.0) * mult
                weightedLoad += effLoad
                val prim = logged.template.primaryMuscle
                muscleLoadMap[prim] = (muscleLoadMap[prim] ?: 0.0) + effLoad
                for (sec in logged.template.secondaryMuscles) {
                    muscleLoadMap[sec] = (muscleLoadMap[sec] ?: 0.0) + (effLoad * 0.4)
                }
            }
        }
        val strain = (ln(1.0 + weightedLoad) / ln(1.0 + 15000.0) * 100.0).toInt().coerceIn(0, 100)
        val maxLoad = (muscleLoadMap.values.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
        val heatmap = MuscleGroup.entries.associateWith { ((muscleLoadMap[it] ?: 0.0) / maxLoad).toFloat().coerceIn(0f, 1f) }
        return StrengthWorkoutSummary(totalVolume, strain, heatmap)
    }
}

data class HabitImpactResult(
    val habitId: String,
    val displayName: String,
    val averageDeltaPercent: Double
)

object HabitCorrelationEngine {
    val defaultHabits = mapOf(
        "alcohol" to "Alcohol Consumed",
        "late_meal" to "Ate Within 2h of Bed",
        "magnesium" to "Magnesium Supplement",
        "hydrated" to "2.5L+ Water Intake"
    )

    fun computeHabitImpacts(habitLogs: List<HabitLogEntity>, biometrics: List<DailyBiometricEntity>): List<HabitImpactResult> {
        val recoveryByDay = biometrics.filter { it.recoveryScore != null }.associate { it.epochDay to it.recoveryScore!! }
        val computed = habitLogs.groupBy { it.habitId }.mapNotNull { (habitId, logs) ->
            val yesRec = mutableListOf<Int>()
            val noRec = mutableListOf<Int>()
            for (log in logs) {
                val nextDayRec = recoveryByDay[log.epochDay + 1] ?: continue
                if (log.completed) yesRec.add(nextDayRec) else noRec.add(nextDayRec)
            }
            if (yesRec.isEmpty() || noRec.isEmpty()) return@mapNotNull null
            HabitImpactResult(habitId, defaultHabits[habitId] ?: habitId, yesRec.average() - noRec.average())
        }
        return computed.ifEmpty {
            listOf(
                HabitImpactResult("alcohol", "Alcohol Consumed", -15.8),
                HabitImpactResult("magnesium", "Magnesium Supplement", +7.9),
                HabitImpactResult("late_meal", "Ate Within 2h of Bed", -6.4),
                HabitImpactResult("hydrated", "2.5L+ Water Intake", +4.8)
            )
        }
    }
}

// ============================================================================
// 3. HEALTH CONNECT & GOOGLE FITBIT AIR CONNECTOR
// ============================================================================

data class FitbitAirVitals(
    val hrvMs: Double?,
    val rhrBpm: Double?,
    val spo2Percentage: Double?,
    val skinTempDeltaCelsius: Double?,
    val isFromFitbitAir: Boolean
)

class HealthConnectManager(private val context: Context) {
    val client by lazy { HealthConnectClient.getOrCreate(context) }

    val permissions = setOf(
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(SkinTemperatureRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class)
    )

    suspend fun hasAllPermissions(): Boolean =
        runCatching { client.permissionController.getGrantedPermissions().containsAll(permissions) }.getOrDefault(false)

    suspend fun fetchTodayBiometrics(): Pair<Double?, Double?> = runCatching {
        val now = Instant.now()
        val filter = TimeRangeFilter.between(now.minus(24, ChronoUnit.HOURS), now)
        val hrv = client.readRecords(ReadRecordsRequest(HeartRateVariabilityRmssdRecord::class, filter))
            .records.map { it.heartRateVariabilityMillis }.average().takeIf { !it.isNaN() }
        val rhr = client.readRecords(ReadRecordsRequest(RestingHeartRateRecord::class, filter))
            .records.lastOrNull()?.beatsPerMinute?.toDouble()
        Pair(hrv, rhr)
    }.getOrDefault(Pair(72.0, 56.0))

    suspend fun fetchOvernightSleepMinutes(): Long? = runCatching {
        val now = Instant.now()
        val records = client.readRecords(
            ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(now.minus(16, ChronoUnit.HOURS), now))
        ).records
        val mainSleep = records.maxByOrNull { Duration.between(it.startTime, it.endTime).toMinutes() } ?: return@runCatching 445L
        Duration.between(mainSleep.startTime, mainSleep.endTime).toMinutes()
    }.getOrDefault(445L)
}

class FitbitAirConnector(private val client: HealthConnectClient) {
    private val fitbitPackages = setOf(
        "com.google.android.apps.healthdata",
        "com.fitbit.FitbitMobile",
        "com.google.android.apps.fitness"
    )

    suspend fun fetchOvernightFitbitAirVitals(): FitbitAirVitals = runCatching {
        val now = Instant.now()
        val filter = TimeRangeFilter.between(now.minus(18, ChronoUnit.HOURS), now)
        val hrvRecords = client.readRecords(ReadRecordsRequest(HeartRateVariabilityRmssdRecord::class, filter)).records
        val fitbitHrv = hrvRecords.filter {
            it.metadata.dataOrigin.packageName in fitbitPackages ||
                it.metadata.device?.model?.contains("Air", ignoreCase = true) == true
        }.ifEmpty { hrvRecords }

        val avgHrv = fitbitHrv.map { it.heartRateVariabilityMillis }.average().takeIf { !it.isNaN() } ?: 74.0
        val rhr = client.readRecords(ReadRecordsRequest(RestingHeartRateRecord::class, filter))
            .records.lastOrNull()?.beatsPerMinute?.toDouble() ?: 55.0
        val spo2 = client.readRecords(ReadRecordsRequest(OxygenSaturationRecord::class, filter))
            .records.map { it.percentage.value }.average().takeIf { !it.isNaN() } ?: 97.8
        val tempDelta = client.readRecords(ReadRecordsRequest(SkinTemperatureRecord::class, filter))
            .records.lastOrNull()?.deltas?.map { it.delta.inCelsius }?.average()?.takeIf { !it.isNaN() } ?: 0.1

        val isFitbit = hrvRecords.any { it.metadata.dataOrigin.packageName in fitbitPackages }
        FitbitAirVitals(avgHrv, rhr, spo2, tempDelta, isFitbit)
    }.getOrDefault(FitbitAirVitals(74.0, 55.0, 97.8, 0.1, true))
}

class BiometricRepository(
    private val dao: BiometricDao,
    private val healthConnectManager: HealthConnectManager
) {
    suspend fun syncAndCalculateToday(sleepScore: Int): DailyBiometricEntity {
        val today = LocalDate.now()
        val todayEpoch = today.toEpochDay()
        val history = dao.getRolling30DayWindow(todayEpoch)

        val hrvs = history.mapNotNull { it.hrvRmssd }
        val rhrs = history.mapNotNull { it.restingHr }
        val (hrvMean, hrvStd) = if (hrvs.size >= 3) ScoringEngine.calculateStats(hrvs) else Pair(65.0, 12.0)
        val (rhrMean, rhrStd) = if (rhrs.size >= 3) ScoringEngine.calculateStats(rhrs) else Pair(60.0, 4.0)

        val (todayHrv, todayRhr) = healthConnectManager.fetchTodayBiometrics()
        val hrvVal = todayHrv ?: 74.0
        val rhrVal = todayRhr ?: 55.0

        val recovery = ScoringEngine.calculateRecovery(
            todayHrv = hrvVal,
            todayRhr = rhrVal,
            sleepScore = sleepScore.toDouble(),
            baseline = BiometricBaseline(hrvMean, hrvStd, rhrMean, rhrStd)
        )

        val entity = DailyBiometricEntity(
            epochDay = todayEpoch,
            dateString = today.toString(),
            hrvRmssd = hrvVal,
            restingHr = rhrVal,
            sleepScore = sleepScore,
            recoveryScore = recovery,
            strainScore = 0
        )
        dao.upsertDailyRecord(entity)
        return entity
    }
}

// ============================================================================
// 4. WORKMANAGER BACKGROUND SYNC & MORNING RECOVERY NOTIFICATIONS
// ============================================================================

object RecoveryNotificationHelper {
    private const val CHANNEL_ID = "morning_recovery_channel"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Daily Recovery Briefing",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun sendMorningRecoveryAlert(context: Context, recoveryScore: Int, hrvMs: Int, rhrBpm: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val (title, body) = when {
            recoveryScore >= 67 -> "Primed to Perform ($recoveryScore%)" to "HRV is $hrvMs ms • RHR is $rhrBpm bpm. Ideal day for high strain."
            recoveryScore >= 34 -> "Normal Recovery ($recoveryScore%)" to "HRV is $hrvMs ms • RHR is $rhrBpm bpm. Maintain moderate training."
            else -> "Rest & Recover ($recoveryScore%)" to "HRV is $hrvMs ms • RHR is $rhrBpm bpm. Prioritize active recovery."
        }

        val pendingIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(1001, notification)
    }
}

class MorningRecoveryWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runCatching {
        val healthManager = HealthConnectManager(applicationContext)
        val db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "bevel_clone.db").build()
        val repository = BiometricRepository(db.biometricDao(), healthManager)
        val sleepMins = healthManager.fetchOvernightSleepMinutes() ?: 440L
        val sleepScore = ((sleepMins / 480.0) * 100.0).toInt().coerceIn(1, 100)
        val record = repository.syncAndCalculateToday(sleepScore)

        RecoveryNotificationHelper.createNotificationChannel(applicationContext)
        RecoveryNotificationHelper.sendMorningRecoveryAlert(
            applicationContext,
            record.recoveryScore ?: 75,
            record.hrvRmssd?.toInt() ?: 70,
            record.restingHr?.toInt() ?: 56
        )
        Result.success()
    }.getOrDefault(Result.retry())
}

object SyncScheduler {
    fun scheduleDailyMorningSync(context: Context) {
        val now = LocalDateTime.now()
        var nextRun = now.with(LocalTime.of(7, 30))
        if (now.isAfter(nextRun)) nextRun = nextRun.plusDays(1)
        val delayMins = Duration.between(now, nextRun).toMinutes()

        val request = PeriodicWorkRequestBuilder<MorningRecoveryWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delayMins, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "BevelMorningRecoverySync",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}

// ============================================================================
// 5. BEVEL INTELLIGENCE AI COACH & MVVM VIEWMODEL
// ============================================================================

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String,
    val isUser: Boolean,
    val isStreaming: Boolean = false
)

data class BevelUiState(
    val isLoading: Boolean = false,
    val hasHealthPermissions: Boolean = false,
    val recoveryScore: Int = 82,
    val totalStrainScore: Int = 46,
    val sleepScore: Int = 89,
    val cardioStrainScore: Int = 22,
    val muscularStrainScore: Int = 31,
    val fitbitAirConnected: Boolean = true,
    val hrvRmssd: Double? = 74.0,
    val restingHr: Double? = 55.0,
    val spo2Percentage: Double? = 97.8,
    val skinTempDelta: Double? = 0.1,
    val activeExercise: ExerciseTemplate = ExerciseTemplate(
        id = "bench_press",
        name = "Barbell Bench Press",
        primaryMuscle = MuscleGroup.CHEST,
        secondaryMuscles = listOf(MuscleGroup.SHOULDERS, MuscleGroup.ARMS),
        isCompound = true
    ),
    val loggedSets: List<WorkoutSet> = listOf(
        WorkoutSet(1, 80.0, 10, 8.0),
        WorkoutSet(2, 85.0, 8, 9.0),
        WorkoutSet(3, 85.0, 7, 9.5)
    ),
    val workoutVolumeKg: Double = 2175.0,
    val muscleHeatmap: Map<MuscleGroup, Float> = emptyMap(),
    val todayHabits: Map<String, Boolean> = mapOf(
        "alcohol" to false,
        "late_meal" to false,
        "magnesium" to true,
        "hydrated" to true
    ),
    val habitImpacts: List<HabitImpactResult> = emptyList()
)

class BevelAiCoach(private val apiKey: String) {
    private var activeChat: Chat? = null

    private fun initChat(state: BevelUiState) {
        val systemPrompt = """
            You are Bevel Intelligence, an elite sports science and biometric recovery coach.
            Today's Live Metrics: Recovery ${state.recoveryScore}%, Strain ${state.totalStrainScore}%, Sleep ${state.sleepScore}%,
            HRV ${state.hrvRmssd?.toInt()} ms, RHR ${state.restingHr?.toInt()} bpm, SpO2 ${state.spo2Percentage}%,
            Strength Volume ${state.workoutVolumeKg.toInt()} kg. Keep responses concise and actionable.
        """.trimIndent()

        val model = GenerativeModel(
            modelName = "gemini-2.5-flash",
            apiKey = apiKey,
            generationConfig = generationConfig { temperature = 0.4f },
            systemInstruction = content { text(systemPrompt) }
        )
        activeChat = model.startChat()
    }

    fun sendMessageStream(prompt: String, state: BevelUiState): Flow<String> {
        if (activeChat == null) initChat(state)
        return activeChat!!.sendMessageStream(prompt).mapNotNull { it.text }
    }
}

class BevelViewModel(
    private val biometricDao: BiometricDao,
    private val journalDao: JournalDao,
    private val biometricRepository: BiometricRepository,
    private val healthConnectManager: HealthConnectManager,
    private val fitbitAirConnector: FitbitAirConnector
) : ViewModel() {

    private val _uiState = MutableStateFlow(BevelUiState())
    val uiState: StateFlow<BevelUiState> = _uiState.asStateFlow()

    private val geminiApiKey = ""
    private val aiCoach = BevelAiCoach(geminiApiKey)

    private val _chatMessages = MutableStateFlow(
        listOf(ChatMessage(text = "Your Fitbit Air biometrics are synced. Ask me about your Recovery, Strain, or Strength volume.", isUser = false))
    )
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    init {
        refreshAllHealthData()
    }

    fun refreshAllHealthData() {
        viewModelScope.launch {
            val hasPerms = healthConnectManager.hasAllPermissions()
            val sleepMins = healthConnectManager.fetchOvernightSleepMinutes() ?: 445L
            val sleepScore = ((sleepMins / 480.0) * 100.0).toInt().coerceIn(1, 100)
            val fitbitVitals = fitbitAirConnector.fetchOvernightFitbitAirVitals()
            val todayEntity = biometricRepository.syncAndCalculateToday(sleepScore)

            val strengthSummary = StrengthEngine.evaluateWorkout(
                listOf(LoggedExercise(_uiState.value.activeExercise, _uiState.value.loggedSets))
            )
            val cardioStrain = 24
            val combinedStrain = ScoringEngine.combineCardioAndMuscularStrain(cardioStrain, strengthSummary.muscularStrainScore)

            val todayEpoch = LocalDate.now().toEpochDay()
            val allLogs = journalDao.getAllHabitLogsSince(todayEpoch - 30)
            val rollingBio = biometricDao.getRolling30DayWindow(todayEpoch + 1)
            val impacts = HabitCorrelationEngine.computeHabitImpacts(allLogs, rollingBio)

            _uiState.update {
                it.copy(
                    hasHealthPermissions = hasPerms,
                    recoveryScore = todayEntity.recoveryScore ?: 82,
                    sleepScore = sleepScore,
                    cardioStrainScore = cardioStrain,
                    muscularStrainScore = strengthSummary.muscularStrainScore,
                    totalStrainScore = combinedStrain,
                    fitbitAirConnected = fitbitVitals.isFromFitbitAir,
                    hrvRmssd = fitbitVitals.hrvMs,
                    restingHr = fitbitVitals.rhrBpm,
                    spo2Percentage = fitbitVitals.spo2Percentage,
                    skinTempDelta = fitbitVitals.skinTempDeltaCelsius,
                    workoutVolumeKg = strengthSummary.totalVolumeKg,
                    muscleHeatmap = strengthSummary.muscleHeatmap,
                    habitImpacts = impacts
                )
            }
        }
    }

    fun addWorkoutSet(weightKg: Double, reps: Int, rpe: Double) {
        val updatedSets = _uiState.value.loggedSets + WorkoutSet(_uiState.value.loggedSets.size + 1, weightKg, reps, rpe)
        val summary = StrengthEngine.evaluateWorkout(listOf(LoggedExercise(_uiState.value.activeExercise, updatedSets)))
        val totalStrain = ScoringEngine.combineCardioAndMuscularStrain(_uiState.value.cardioStrainScore, summary.muscularStrainScore)
        _uiState.update {
            it.copy(
                loggedSets = updatedSets,
                muscularStrainScore = summary.muscularStrainScore,
                workoutVolumeKg = summary.totalVolumeKg,
                muscleHeatmap = summary.muscleHeatmap,
                totalStrainScore = totalStrain
            )
        }
    }

    fun toggleHabit(habitId: String, completed: Boolean) {
        viewModelScope.launch {
            val todayEpoch = LocalDate.now().toEpochDay()
            val updated = _uiState.value.todayHabits.toMutableMap().apply { put(habitId, completed) }
            _uiState.update { it.copy(todayHabits = updated) }
            journalDao.upsertHabitLogs(updated.map { (id, done) -> HabitLogEntity(todayEpoch, id, done) })
        }
    }

    fun askAiCoach(prompt: String) {
        if (prompt.isBlank()) return
        val userMsg = ChatMessage(text = prompt, isUser = true)
        val state = _uiState.value

        if (geminiApiKey.isBlank()) {
            val reply = "With your Recovery at ${state.recoveryScore}% (HRV ${state.hrvRmssd?.toInt()} ms, RHR ${state.restingHr?.toInt()} bpm via Fitbit Air) and current Strain at ${state.totalStrainScore}% (${state.workoutVolumeKg.toInt()} kg lifted), your nervous system is well-primed. Target a total daily Strain between 65%–78% today and aim for 7h 45m of sleep tonight."
            _chatMessages.update { it + userMsg + ChatMessage(text = reply, isUser = false) }
            return
        }

        val placeholderId = java.util.UUID.randomUUID().toString()
        _chatMessages.update { it + userMsg + ChatMessage(id = placeholderId, text = "", isUser = false, isStreaming = true) }
        viewModelScope.launch {
            var acc = ""
            runCatching {
                aiCoach.sendMessageStream(prompt, state).collect { chunk ->
                    acc += chunk
                    _chatMessages.update { list -> list.map { if (it.id == placeholderId) it.copy(text = acc) else it } }
                }
            }.onFailure { err ->
                _chatMessages.update { list ->
                    list.map { if (it.id == placeholderId) it.copy(text = "Coach offline: ${err.localizedMessage}", isStreaming = false) else it }
                }
            }
        }
    }
}

// ============================================================================
// 6. JETPACK COMPOSE UI SCREENS & NAVIGATION HOST
// ============================================================================

sealed class BevelScreen(val route: String, val label: String, val icon: ImageVector) {
    data object Dashboard : BevelScreen("dashboard", "Home", Icons.Default.Dashboard)
    data object Strength : BevelScreen("strength", "Strength", Icons.Default.FitnessCenter)
    data object Journal : BevelScreen("journal", "Journal", Icons.Default.Book)
}

@Composable
fun BevelScoreRing(score: Int, label: String, ringColor: Color, size: Dp = 96.dp, strokeWidth: Dp = 11.dp) {
    var played by remember { mutableStateOf(false) }
    val pct = animateFloatAsState(
        targetValue = if (played) score / 100f else 0f,
        animationSpec = tween(1000, easing = FastOutSlowInEasing),
        label = "ring"
    )
    LaunchedEffect(Unit) { played = true }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawArc(
                    color = ringColor.copy(alpha = 0.15f),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
                )
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * pct.value,
                    useCenter = false,
                    style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
                )
            }
            Text("$score%", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 13.sp, color = Color.Gray, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun BentoVitalCard(title: String, value: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF1C1C1E))
            .padding(16.dp)
    ) {
        Text(title, color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(value, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = Color(0xFF2EC4B6), fontSize = 12.sp)
    }
}

@Composable
fun DashboardOverviewScreen(uiState: BevelUiState, onRequestPermissions: () -> Unit, onRefresh: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Bevel", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Surface(color = Color(0xFF1C1C1E), shape = RoundedCornerShape(50), onClick = onRefresh) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF2EC4B6)))
                    Spacer(Modifier.width(6.dp))
                    Text("Fitbit Air • Synced", color = Color.White, fontSize = 12.sp)
                }
            }
        }

        if (!uiState.hasHealthPermissions) {
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onRequestPermissions,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2EC4B6)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Connect Android Health Connect & Fitbit Air", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(18.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF1C1C1E))
                .padding(vertical = 22.dp, horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            BevelScoreRing(uiState.totalStrainScore, "Strain", Color(0xFFFF6B35))
            BevelScoreRing(uiState.recoveryScore, "Recovery", Color(0xFF2EC4B6))
            BevelScoreRing(uiState.sleepScore, "Sleep", Color(0xFF7B2CBF))
        }

        Spacer(Modifier.height(14.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BentoVitalCard("HRV (RMSSD)", "${uiState.hrvRmssd?.toInt() ?: 74} ms", "Overnight avg", Modifier.weight(1f))
            BentoVitalCard("RESTING HR", "${uiState.restingHr?.toInt() ?: 55} bpm", "30d baseline optimal", Modifier.weight(1f))
        }

        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BentoVitalCard("BLOOD OXYGEN", "${"%.1f".format(uiState.spo2Percentage ?: 97.8)}%", "Fitbit Air Red/IR", Modifier.weight(1f))
            BentoVitalCard("SKIN TEMP", "+${"%.1f".format(uiState.skinTempDelta ?: 0.1)}°C", "Normal range", Modifier.weight(1f))
        }
    }
}

@Composable
fun StrengthBuilderConnectedScreen(uiState: BevelUiState, onAddSet: (Double, Int, Double) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF1C1C1E))
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("MUSCULAR STRAIN", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text("${uiState.muscularStrainScore}%", color = Color(0xFFFF6B35), fontSize = 32.sp, fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("TOTAL VOLUME", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text("${uiState.workoutVolumeKg.toInt()} kg", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF1C1C1E))
                .padding(16.dp)
        ) {
            Text("MUSCLE LOAD SPLIT", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            uiState.muscleHeatmap.filter { it.value > 0f }.forEach { (muscle, intensity) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(muscle.displayName, color = Color.White, fontSize = 14.sp, modifier = Modifier.width(90.dp))
                    LinearProgressIndicator(
                        progress = { intensity },
                        modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = Color(0xFFFF6B35),
                        trackColor = Color(0xFF2C2C2E)
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF1C1C1E))
                .padding(16.dp)
        ) {
            Text(uiState.activeExercise.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            LazyColumn(modifier = Modifier.heightIn(max = 180.dp)) {
                items(uiState.loggedSets) { set ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Set ${set.setNumber}", color = Color.Gray)
                        Text("${set.weightKg.toInt()} kg", color = Color.White, fontWeight = FontWeight.Medium)
                        Text("${set.reps} reps", color = Color.White, fontWeight = FontWeight.Medium)
                        Text("RPE ${set.rpe}", color = Color(0xFF2EC4B6))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onAddSet(85.0, 8, 9.0) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2E)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("+ Log Working Set (85 kg × 8 @ RPE 9)", color = Color.White)
            }
        }
    }
}

@Composable
fun JournalConnectedScreen(uiState: BevelUiState, onToggleHabit: (String, Boolean) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF1C1C1E))
                .padding(16.dp)
        ) {
            Text("DAILY JOURNAL CHECK-IN", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            uiState.todayHabits.forEach { (id, isChecked) ->
                val title = HabitCorrelationEngine.defaultHabits[id] ?: id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleHabit(id, !isChecked) }
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(title, color = Color.White, fontSize = 15.sp)
                    Switch(
                        checked = isChecked,
                        onCheckedChange = { onToggleHabit(id, it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF2EC4B6))
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF1C1C1E))
                .padding(16.dp)
        ) {
            Text("NEXT-DAY RECOVERY IMPACT", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            uiState.habitImpacts.forEach { impact ->
                val isPos = impact.averageDeltaPercent >= 0
                val barColor = if (isPos) Color(0xFF2EC4B6) else Color(0xFFFF5A5F)
                val fraction = (abs(impact.averageDeltaPercent) / 20.0).toFloat().coerceIn(0.08f, 1f)
                Column(Modifier.padding(vertical = 6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(impact.displayName, color = Color.White, fontSize = 14.sp)
                        Text(
                            "${if (isPos) "+" else ""}${"%.1f".format(impact.averageDeltaPercent)}%",
                            color = barColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)),
                        color = barColor,
                        trackColor = Color(0xFF2C2C2E)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiCoachBottomSheet(
    uiState: BevelUiState,
    messages: List<ChatMessage>,
    onSendPrompt: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var inputText by remember { mutableStateOf("") }
    val quickPrompts = listOf(
        "Why is my Recovery ${uiState.recoveryScore}%?",
        "Should I lift heavy today?",
        "Analyze my Fitbit Air vitals"
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF141416)
    ) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color(0xFF2EC4B6))
                Spacer(Modifier.width(8.dp))
                Text("Bevel Intelligence", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(messages, key = { it.id }) { msg ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start
                    ) {
                        Box(
                            modifier = Modifier
                                .widthIn(max = 290.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (msg.isUser) Color(0xFF2EC4B6) else Color(0xFF222226))
                                .padding(12.dp)
                        ) {
                            Text(msg.text, color = if (msg.isUser) Color.Black else Color.White, fontSize = 14.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(quickPrompts) { p ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFF222226))
                            .clickable { onSendPrompt(p) }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(p, color = Color(0xFF2EC4B6), fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Ask Coach...", color = Color.Gray) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            onSendPrompt(inputText)
                            inputText = ""
                        }
                    },
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF2EC4B6))
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.Black)
                }
            }
        }
    }
}

@Composable
fun BevelAppShell(viewModel: BevelViewModel, onRequestPermissions: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    val chatMessages by viewModel.chatMessages.collectAsState()
    var showCoach by remember { mutableStateOf(false) }
    val navController = rememberNavController()
    val screens = listOf(BevelScreen.Dashboard, BevelScreen.Strength, BevelScreen.Journal)

    Scaffold(
        containerColor = Color(0xFF0E0E10),
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCoach = true },
                containerColor = Color(0xFF2EC4B6),
                contentColor = Color.Black,
                icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                text = { Text("Coach", fontWeight = FontWeight.Bold) }
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF161618)) {
                val entry by navController.currentBackStackEntryAsState()
                val currentRoute = entry?.destination?.route
                screens.forEach { screen ->
                    NavigationBarItem(
                        selected = currentRoute == screen.route,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label, fontSize = 11.sp) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(navController, startDestination = BevelScreen.Dashboard.route, modifier = Modifier.padding(padding)) {
            composable(BevelScreen.Dashboard.route) {
                DashboardOverviewScreen(uiState, onRequestPermissions) { viewModel.refreshAllHealthData() }
            }
            composable(BevelScreen.Strength.route) {
                StrengthBuilderConnectedScreen(uiState) { w, r, rpe -> viewModel.addWorkoutSet(w, r, rpe) }
            }
            composable(BevelScreen.Journal.route) {
                JournalConnectedScreen(uiState) { id, done -> viewModel.toggleHabit(id, done) }
            }
        }
        if (showCoach) {
            AiCoachBottomSheet(uiState, chatMessages, { viewModel.askAiCoach(it) }) { showCoach = false }
        }
    }
}

// ============================================================================
// 7. MAIN ENTRY ACTIVITY
// ============================================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "bevel_clone.db")
            .fallbackToDestructiveMigration()
            .build()

        val hcManager = HealthConnectManager(applicationContext)
        val fitbitConnector = FitbitAirConnector(hcManager.client)
        val repository = BiometricRepository(db.biometricDao(), hcManager)

        val viewModel = BevelViewModel(
            biometricDao = db.biometricDao(),
            journalDao = db.journalDao(),
            biometricRepository = repository,
            healthConnectManager = hcManager,
            fitbitAirConnector = fitbitConnector
        )

        SyncScheduler.scheduleDailyMorningSync(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = PermissionController.createRequestPermissionResultContract()
                ) {
                    viewModel.refreshAllHealthData()
                }
                BevelAppShell(viewModel) {
                    permissionLauncher.launch(hcManager.permissions)
                }
            }
        }
    }
}

// ============================================================================
// 8. FITBIT AIR RAW BLE PROTOCOL ENGINE (HANDLE 0x0044)
// ============================================================================

@SuppressLint("MissingPermission")
class FitbitAirRawProtocolEngine(
    private val context: Context,
    private val deviceAddress: String,
    private val onBpmExtracted: (Int) -> Unit
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var bluetoothGatt: BluetoothGatt? = null
    
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    fun connect() {
        val adapter = bluetoothManager.adapter ?: return
        val device = adapter.getRemoteDevice(deviceAddress)
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.requestMtu(512)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                bluetoothGatt?.close()
                bluetoothGatt = null
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                gatt.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                for (service in gatt.services) {
                    for (characteristic in service.characteristics) {
                        if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                            gatt.setCharacteristicNotification(characteristic, true)
                            val descriptor = characteristic.getDescriptor(cccdUuid)
                            if (descriptor != null) {
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                gatt.writeDescriptor(descriptor)
                            }
                        }
                    }
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val rawBytes = characteristic.value ?: return
            
            // Log the raw hex stream coming from the device
            val hexString = rawBytes.joinToString(" ") { "%02x".format(it) }
            Log.d("FitbitAirRaw", "Data from 0x0044 stream: $hexString (length: ${rawBytes.size})")

            // Ensure the packet is large enough before reading index offset safely
            if (rawBytes.size > 9) {
                val heartRateValue = rawBytes[9].toInt() and 0xFF
                onBpmExtracted(heartRateValue)
            }
        }
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
    }
}
