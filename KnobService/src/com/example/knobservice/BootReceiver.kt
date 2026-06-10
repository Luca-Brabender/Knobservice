import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.knobservice.KnobService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("KnobService", "Boot completed, Starte KnobService...")
            val serviceIntent = Intent(context, KnobService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}