package cn.guahao

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity

/** Debug-only host for synthetic UI tests, without foreground hospital recovery. */
class SchedulePreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
