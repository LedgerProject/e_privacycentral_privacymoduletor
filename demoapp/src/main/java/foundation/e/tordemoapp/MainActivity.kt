package foundation.e.tordemoapp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import foundation.e.privacymodules.ipscrambler.IpScramblerModule

class MainActivity : AppCompatActivity(), IpScramblerModule.Listener {

    private var lblStatus: TextView? = null
    private var lblTraffic: TextView? = null
    private var mTxtOrbotLog: TextView? = null
    private var mBtnStart: Button? = null
    private var mBtnStop: Button? = null
    private lateinit var ipScramblerModule: IpScramblerModule

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ipScramblerModule = IpScramblerModule(this)

        ipScramblerModule.addListener(this)


        lblStatus = findViewById(R.id.label_status)
        lblTraffic = findViewById(R.id.label_traffic)
        mTxtOrbotLog = findViewById(R.id.orbot_log)
        mBtnStart = findViewById(R.id.button_start)
        mBtnStop = findViewById(R.id.button_stop)

        mBtnStart?.setOnClickListener { v: View? ->
            ipScramblerModule.prepareAndroidVpn()?.let { startActivityForResult(it, 3)}
                ?: run  {
                    ipScramblerModule.start()
                }
        }

        mBtnStop?.setOnClickListener {
            ipScramblerModule.stop()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when(requestCode) {
            3 -> if (resultCode == Activity.RESULT_OK) {
                ipScramblerModule.start()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ipScramblerModule.requestStatus()
    }


    private var logHistory = ""
    override fun log(message: String) {
        logHistory = message + "\n" + logHistory
        mTxtOrbotLog?.text = logHistory
    }

    override fun onStatusChanged(newStatus: IpScramblerModule.Status) {
        lblStatus?.text = newStatus.toString()
    }

    override fun onTrafficUpdate(upload: Long, download: Long, read: Long, write: Long) {
        lblTraffic?.text = "↑ ${write/1000}kB (${upload/1000}kB/s)  ↓ ${read/1000}kB (${download/1000}kB/s)"
    }
}

