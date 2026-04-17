package helium314.keyboard.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Transparent Activity that requests RECORD_AUDIO permission and finishes immediately.
 * IMEs (InputMethodService) cannot show permission dialogs, so this Activity acts as a proxy.
 */
class PermissionRequestActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    companion object {
        fun createIntent(context: Context): Intent {
            return Intent(context, PermissionRequestActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }
}
