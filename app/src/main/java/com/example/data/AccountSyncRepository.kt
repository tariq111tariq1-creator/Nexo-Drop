package com.example.data

import android.content.Context
import android.os.Build
import androidx.annotation.Keep
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.example.R
import com.example.ui.theme.ThemeMode
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ServerTimestamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Date

@Keep
@IgnoreExtraProperties
data class UserCloudProfile(
    val id: String = "",
    val uid: String = "",
    val email: String = "",
    val displayName: String = "",
    val photoUrl: String = "",
    val preferredSaveMode: String = SaveLocationMode.APP_DOWNLOADS.name,
    val preferredThemeMode: String = ThemeMode.SYSTEM.name,
    val stripExifOnSend: Boolean = false,
    @ServerTimestamp val createdAt: Date? = null,
    @ServerTimestamp val updatedAt: Date? = null
)

@Keep
@IgnoreExtraProperties
data class RegisteredDevice(
    val id: String = "",
    val userId: String = "",
    val deviceId: String = "",
    val deviceName: String = "",
    val model: String = "",
    val platformVersion: Int = Build.VERSION.SDK_INT,
    @ServerTimestamp val lastActiveAt: Date? = null,
    @ServerTimestamp val createdAt: Date? = null,
    @ServerTimestamp val updatedAt: Date? = null
)

@Keep
@IgnoreExtraProperties
data class SyncedTransferRecord(
    val id: String = "",
    val userId: String = "",
    val deviceId: String = "",
    val deviceName: String = "",
    val fileName: String = "",
    val fileSize: Long = 0L,
    val mimeType: String = "application/octet-stream",
    val category: String = FileCategory.OTHER.name,
    val direction: String = TransferDirection.SENT.name,
    val peerDeviceName: String = "",
    val groupName: String = "",
    val status: String = TransferStatus.COMPLETED.name,
    val sha256: String = "",
    val transferredAt: Long = 0L,
    @ServerTimestamp val createdAt: Date? = null,
    @ServerTimestamp val updatedAt: Date? = null
)

data class AccountAuthState(
    val signedIn: Boolean = false,
    val uid: String = "",
    val email: String = "",
    val displayName: String = "",
    val photoUrl: String = "",
    val busy: Boolean = false,
    val error: String? = null
)

sealed class CloudSyncUiState<out T> {
    data object SignedOut : CloudSyncUiState<Nothing>()
    data object Loading : CloudSyncUiState<Nothing>()
    data class Success<T>(val data: T) : CloudSyncUiState<T>()
    data class Error(val message: String) : CloudSyncUiState<Nothing>()
}

class AccountSyncRepository(private val context: Context) {

    private val auth: FirebaseAuth? by lazy {
        runCatching {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context)
            }
            FirebaseAuth.getInstance()
        }.getOrNull()
    }

    private val firestore: FirebaseFirestore? by lazy {
        runCatching {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context)
            }
            val dbId = context.getString(R.string.firestore_database_id)
            FirebaseFirestore.getInstance(dbId).apply {
                firestoreSettings = FirebaseFirestoreSettings.Builder().build()
            }
        }.getOrNull()
    }

    private val _authState = MutableStateFlow(readCurrentAuthState())
    val authState: StateFlow<AccountAuthState> = _authState.asStateFlow()

    private val authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        val user = firebaseAuth.currentUser
        _authState.value = user.toAccountAuthState(
            busy = _authState.value.busy,
            error = null
        )
    }

    init {
        runCatching { auth?.addAuthStateListener(authListener) }
    }

    private fun readCurrentAuthState(): AccountAuthState {
        return runCatching { auth?.currentUser }.getOrNull().toAccountAuthState()
    }

    private fun FirebaseUser?.toAccountAuthState(
        busy: Boolean = false,
        error: String? = null
    ): AccountAuthState {
        return if (this == null) {
            AccountAuthState(signedIn = false, busy = busy, error = error)
        } else {
            AccountAuthState(
                signedIn = true,
                uid = uid,
                email = email.orEmpty(),
                displayName = displayName.orEmpty().ifBlank { email.orEmpty().substringBefore("@").ifBlank { "Google User" } },
                photoUrl = photoUrl?.toString().orEmpty(),
                busy = busy,
                error = error
            )
        }
    }

    suspend fun signInWithGoogle(activityContext: Context): Result<AccountAuthState> {
        val firebaseAuth = auth ?: return Result.failure(IllegalStateException("Firebase Auth unavailable"))
        _authState.value = _authState.value.copy(busy = true, error = null)
        return try {
            val webClientId = context.getString(R.string.default_web_client_id)
            val option = GetSignInWithGoogleOption.Builder(webClientId).build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build()

            val credentialManager = CredentialManager.create(activityContext)
            val response = credentialManager.getCredential(activityContext, request)
            val cred = response.credential
            if (cred !is CustomCredential || cred.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                throw IllegalStateException("Unexpected credential type received")
            }
            val googleCred = GoogleIdTokenCredential.createFrom(cred.data)
            val authCred = GoogleAuthProvider.getCredential(googleCred.idToken, null)
            val authResult = firebaseAuth.signInWithCredential(authCred).await()
            val nextState = authResult.user.toAccountAuthState(busy = false, error = null)
            _authState.value = nextState
            Result.success(nextState)
        } catch (e: GetCredentialCancellationException) {
            _authState.value = _authState.value.copy(busy = false)
            Result.failure(e)
        } catch (e: NoCredentialException) {
            val msg = e.localizedMessage ?: "No Google account found on this device"
            _authState.value = _authState.value.copy(busy = false, error = msg)
            Result.failure(e)
        } catch (e: GetCredentialException) {
            val msg = e.localizedMessage ?: "Google sign-in interrupted"
            _authState.value = _authState.value.copy(busy = false, error = msg)
            Result.failure(e)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val msg = e.localizedMessage ?: "Google sign-in failed"
            _authState.value = _authState.value.copy(busy = false, error = msg)
            Result.failure(e)
        }
    }

    suspend fun signOut(): Result<Unit> = withContext(NonCancellable) {
        runCatching {
            auth?.signOut()
            runCatching {
                CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
            }
            _authState.value = AccountAuthState(signedIn = false)
        }
    }

    fun observeCloudProfile(uid: String): Flow<CloudSyncUiState<UserCloudProfile?>> {
        if (uid.isBlank()) return flowOf(CloudSyncUiState.SignedOut)
        val db = firestore ?: return flowOf(CloudSyncUiState.Error("Firestore unavailable"))
        return callbackFlow {
            trySend(CloudSyncUiState.Loading)
            val reg = db.collection("users").document(uid)
                .addSnapshotListener { snap, err ->
                    if (err != null) {
                        val msg = (err as? FirebaseFirestoreException)?.localizedMessage
                            ?: err.localizedMessage
                            ?: "Unable to load profile"
                        trySend(CloudSyncUiState.Error(msg))
                        return@addSnapshotListener
                    }
                    if (snap == null || !snap.exists()) {
                        trySend(CloudSyncUiState.Success(null))
                    } else {
                        trySend(CloudSyncUiState.Success(snap.toUserCloudProfile()))
                    }
                }
            awaitClose { reg.remove() }
        }
    }

    fun observeDevices(uid: String): Flow<CloudSyncUiState<List<RegisteredDevice>>> {
        if (uid.isBlank()) return flowOf(CloudSyncUiState.SignedOut)
        val db = firestore ?: return flowOf(CloudSyncUiState.Error("Firestore unavailable"))
        return callbackFlow {
            trySend(CloudSyncUiState.Loading)
            val reg = db.collection("users").document(uid)
                .collection("devices")
                .orderBy("lastActiveAt", Query.Direction.DESCENDING)
                .limit(20)
                .addSnapshotListener { snap, err ->
                    if (err != null) {
                        val msg = (err as? FirebaseFirestoreException)?.localizedMessage
                            ?: err.localizedMessage
                            ?: "Unable to load devices"
                        trySend(CloudSyncUiState.Error(msg))
                        return@addSnapshotListener
                    }
                    val list = snap?.documents?.mapNotNull { doc ->
                        runCatching { doc.toRegisteredDevice() }.getOrNull()
                    }.orEmpty()
                    trySend(CloudSyncUiState.Success(list))
                }
            awaitClose { reg.remove() }
        }
    }

    fun observeSyncedTransfers(uid: String): Flow<CloudSyncUiState<List<SyncedTransferRecord>>> {
        if (uid.isBlank()) return flowOf(CloudSyncUiState.SignedOut)
        val db = firestore ?: return flowOf(CloudSyncUiState.Error("Firestore unavailable"))
        return callbackFlow {
            trySend(CloudSyncUiState.Loading)
            val reg = db.collection("users").document(uid)
                .collection("synced_transfers")
                .orderBy("transferredAt", Query.Direction.DESCENDING)
                .limit(100)
                .addSnapshotListener { snap, err ->
                    if (err != null) {
                        val msg = (err as? FirebaseFirestoreException)?.localizedMessage
                            ?: err.localizedMessage
                            ?: "Unable to load synced transfers"
                        trySend(CloudSyncUiState.Error(msg))
                        return@addSnapshotListener
                    }
                    val list = snap?.documents?.mapNotNull { doc ->
                        runCatching { doc.toSyncedTransferRecord() }.getOrNull()
                    }.orEmpty()
                    trySend(CloudSyncUiState.Success(list))
                }
            awaitClose { reg.remove() }
        }
    }

    suspend fun upsertCloudProfileAndDevice(settings: UserSettings): Result<Unit> = withContext(Dispatchers.IO) {
        val currentUser = auth?.currentUser ?: return@withContext Result.failure(IllegalStateException("Not signed in"))
        val db = firestore ?: return@withContext Result.failure(IllegalStateException("Firestore unavailable"))
        val uid = currentUser.uid
        if (!isValidDocId(uid)) return@withContext Result.failure(IllegalArgumentException("Invalid user ID"))

        try {
            val safeName = currentUser.displayName?.trim()?.ifBlank { null }
                ?: currentUser.email?.substringBefore("@")?.trim()?.ifBlank { null }
                ?: settings.deviceName.trim().ifBlank { "NexoDrop User" }
            val profileRef = db.collection("users").document(uid)
            val existingProfile = profileRef.get().await()
            val commonProfileFields = hashMapOf<String, Any>(
                "uid" to uid,
                "email" to (currentUser.email ?: "").take(200),
                "displayName" to safeName.take(80),
                "photoUrl" to (currentUser.photoUrl?.toString() ?: "").take(500),
                "preferredSaveMode" to validSaveMode(settings.saveMode).name,
                "preferredThemeMode" to settings.themeMode.name,
                "stripExifOnSend" to settings.stripExifOnSend,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (existingProfile.exists()) {
                profileRef.update(commonProfileFields).await()
            } else {
                commonProfileFields["createdAt"] = FieldValue.serverTimestamp()
                profileRef.set(commonProfileFields).await()
            }

            val devId = sanitizeDocId(settings.deviceId.ifBlank { "device-${Build.MODEL}" })
            val devRef = profileRef.collection("devices").document(devId)
            val existingDev = devRef.get().await()
            val commonDevFields = hashMapOf<String, Any>(
                "userId" to uid,
                "deviceId" to devId,
                "deviceName" to settings.deviceName.trim().ifBlank { Build.MODEL ?: "Android" }.take(80),
                "model" to (Build.MODEL ?: "Android").trim().ifBlank { "Android" }.take(80),
                "platformVersion" to Build.VERSION.SDK_INT.coerceIn(24, 100),
                "lastActiveAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (existingDev.exists()) {
                devRef.update(commonDevFields).await()
            } else {
                commonDevFields["createdAt"] = FieldValue.serverTimestamp()
                devRef.set(commonDevFields).await()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    suspend fun removeCloudDevice(deviceId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val uid = auth?.currentUser?.uid ?: return@withContext Result.failure(IllegalStateException("Not signed in"))
        val db = firestore ?: return@withContext Result.failure(IllegalStateException("Firestore unavailable"))
        if (!isValidDocId(deviceId)) return@withContext Result.failure(IllegalArgumentException("Invalid device ID"))
        try {
            db.collection("users").document(uid).collection("devices").document(deviceId).delete().await()
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    suspend fun syncTransfersToCloud(
        settings: UserSettings,
        records: List<TransferRecordEntity>
    ): Result<Int> = withContext(Dispatchers.IO) {
        val currentUser = auth?.currentUser ?: return@withContext Result.failure(IllegalStateException("Not signed in"))
        val db = firestore ?: return@withContext Result.failure(IllegalStateException("Firestore unavailable"))
        val uid = currentUser.uid
        val devId = sanitizeDocId(settings.deviceId.ifBlank { "device-${Build.MODEL}" }).take(64)
        val devName = settings.deviceName.trim().ifBlank { Build.MODEL ?: "Android" }.take(80)
        val transfersCol = db.collection("users").document(uid).collection("synced_transfers")

        try {
            var syncedCount = 0
            records.take(40).forEach { rec ->
                val rawKey = "${devId}_${rec.transferId}_${rec.timestamp}"
                val docId = sanitizeDocId(rawKey).take(128)
                val docRef = transfersCol.document(docId)
                val snap = docRef.get().await()
                val safeCategory = runCatching { FileCategory.valueOf(rec.category).name }.getOrDefault(FileCategory.OTHER.name)
                val safeDir = if (rec.direction == TransferDirection.RECEIVED.name) {
                    TransferDirection.RECEIVED.name
                } else {
                    TransferDirection.SENT.name
                }
                val safeStatus = runCatching { TransferStatus.valueOf(rec.status).name }.getOrDefault(TransferStatus.COMPLETED.name)
                val fields = hashMapOf<String, Any>(
                    "userId" to uid,
                    "deviceId" to devId,
                    "deviceName" to devName,
                    "fileName" to rec.fileName.trim().ifBlank { "file" }.take(255),
                    "fileSize" to rec.fileSize.coerceAtLeast(0L),
                    "mimeType" to rec.mimeType.trim().ifBlank { "application/octet-stream" }.take(120),
                    "category" to safeCategory,
                    "direction" to safeDir,
                    "peerDeviceName" to rec.peerDeviceName.trim().ifBlank { "Group Peer" }.take(120),
                    "groupName" to rec.groupName.trim().take(120),
                    "status" to safeStatus,
                    "sha256" to rec.sha256.trim().take(128),
                    "transferredAt" to rec.timestamp.coerceAtLeast(0L),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (!snap.exists()) {
                    fields["createdAt"] = FieldValue.serverTimestamp()
                    docRef.set(fields).await()
                    syncedCount++
                }
            }
            Result.success(syncedCount)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    suspend fun fetchLatestCloudProfile(): Result<UserCloudProfile?> = withContext(Dispatchers.IO) {
        val uid = auth?.currentUser?.uid ?: return@withContext Result.failure(IllegalStateException("Not signed in"))
        val db = firestore ?: return@withContext Result.failure(IllegalStateException("Firestore unavailable"))
        try {
            val snap = db.collection("users").document(uid).get().await()
            if (!snap.exists()) Result.success(null) else Result.success(snap.toUserCloudProfile())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    private fun validSaveMode(mode: SaveLocationMode): SaveLocationMode {
        return when (mode) {
            SaveLocationMode.APP_DOWNLOADS,
            SaveLocationMode.CUSTOM_TREE,
            SaveLocationMode.CATEGORY_SMART,
            SaveLocationMode.PRIVATE_VAULT -> mode
        }
    }

    private fun isValidDocId(id: String): Boolean {
        return id.isNotBlank() &&
            id.length <= 128 &&
            !id.contains('/') &&
            !id.contains('\\') &&
            id != "." &&
            id != ".."
    }

    private fun sanitizeDocId(raw: String): String {
        val cleaned = raw.replace(Regex("[^a-zA-Z0-9_-]"), "_").trim('_')
        return if (cleaned.isBlank() || cleaned == "." || cleaned == "..") "id_default" else cleaned.take(120)
    }

    private fun DocumentSnapshot.toUserCloudProfile(): UserCloudProfile {
        val b = DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
        return UserCloudProfile(
            id = id,
            uid = getString("uid").orEmpty(),
            email = getString("email").orEmpty(),
            displayName = getString("displayName").orEmpty(),
            photoUrl = getString("photoUrl").orEmpty(),
            preferredSaveMode = getString("preferredSaveMode") ?: SaveLocationMode.APP_DOWNLOADS.name,
            preferredThemeMode = getString("preferredThemeMode") ?: ThemeMode.SYSTEM.name,
            stripExifOnSend = getBoolean("stripExifOnSend") ?: false,
            createdAt = getDate("createdAt", b),
            updatedAt = getDate("updatedAt", b)
        )
    }

    private fun DocumentSnapshot.toRegisteredDevice(): RegisteredDevice {
        val b = DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
        return RegisteredDevice(
            id = id,
            userId = getString("userId").orEmpty(),
            deviceId = getString("deviceId").orEmpty(),
            deviceName = getString("deviceName").orEmpty(),
            model = getString("model").orEmpty(),
            platformVersion = (getLong("platformVersion") ?: Build.VERSION.SDK_INT.toLong()).toInt(),
            lastActiveAt = getDate("lastActiveAt", b),
            createdAt = getDate("createdAt", b),
            updatedAt = getDate("updatedAt", b)
        )
    }

    private fun DocumentSnapshot.toSyncedTransferRecord(): SyncedTransferRecord {
        val b = DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
        return SyncedTransferRecord(
            id = id,
            userId = getString("userId").orEmpty(),
            deviceId = getString("deviceId").orEmpty(),
            deviceName = getString("deviceName").orEmpty(),
            fileName = getString("fileName").orEmpty(),
            fileSize = getLong("fileSize") ?: 0L,
            mimeType = getString("mimeType").orEmpty(),
            category = getString("category") ?: FileCategory.OTHER.name,
            direction = getString("direction") ?: TransferDirection.SENT.name,
            peerDeviceName = getString("peerDeviceName").orEmpty(),
            groupName = getString("groupName").orEmpty(),
            status = getString("status") ?: TransferStatus.COMPLETED.name,
            sha256 = getString("sha256").orEmpty(),
            transferredAt = getLong("transferredAt") ?: 0L,
            createdAt = getDate("createdAt", b),
            updatedAt = getDate("updatedAt", b)
        )
    }
}
