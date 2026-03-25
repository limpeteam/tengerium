package com.limpe.tengerium

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.limpe.tengerium.databinding.ActivityCrashReportBinding

class CrashReportActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(TengeriumApp.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityCrashReportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val stackTrace = intent.getStringExtra("stacktrace") ?: getString(R.string.no_trace_available)
        binding.tvStackTrace.text = stackTrace

        binding.btnSendReport.setOnClickListener {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.crash_report_subject))
                putExtra(Intent.EXTRA_TEXT, getString(R.string.crash_report_body_format, android.os.Build.MODEL, android.os.Build.VERSION.RELEASE, stackTrace))
            }
            startActivity(Intent.createChooser(sendIntent, getString(R.string.send_report_via)))
        }

        binding.btnRestart.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
            finish()
        }
    }
}
