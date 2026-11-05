package cn.devcxl.photosync.album

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import timber.log.Timber

/** A persisted read-only directory grant, scoped to a currently mounted removable camera volume. */
internal class MassStorageAccess(private val activity: ComponentActivity, private val changed: () -> Unit) {
    private val preferences = activity.getSharedPreferences("camera-file-access", Context.MODE_PRIVATE)
    private val permission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { changed() }
    private val picker = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == ComponentActivity.RESULT_OK && uri != null &&
            uri.authority == "com.android.externalstorage.documents") {
            activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            preferences.edit().putString("tree", uri.toString()).apply()
            Timber.tag("USB_COMPAT").i("FILE_FOLDER_GRANTED readOnly=true")
            if (Build.VERSION.SDK_INT >= 29) permission.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
            changed()
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { changed() }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED); addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT); addAction(Intent.ACTION_MEDIA_REMOVED)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(activity, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    private fun volumes() = activity.getSystemService(StorageManager::class.java).storageVolumes.filter {
        it.isRemovable && it.state in setOf(Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY)
    }

    fun available(): Boolean = volumes().any { it.state == Environment.MEDIA_MOUNTED }

    fun tree(): Uri? {
        val mounted = volumes().mapNotNull { it.uuid }.toSet()
        val granted = activity.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }
        val saved = preferences.getString("tree", null)?.let(Uri::parse)
        return (listOfNotNull(saved) + granted).distinct().firstOrNull { uri ->
            uri.authority == "com.android.externalstorage.documents" && runCatching {
                DocumentsContract.getTreeDocumentId(uri).substringBefore(':') in mounted && uri in granted
            }.getOrDefault(false)
        }
    }

    fun choose() {
        val volume = volumes().firstOrNull { it.state == Environment.MEDIA_MOUNTED }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        if (Build.VERSION.SDK_INT >= 26 && volume?.uuid != null) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
            DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "${volume.uuid}:DCIM"))
        picker.launch(intent)
    }

    fun close() { activity.unregisterReceiver(receiver) }
}
