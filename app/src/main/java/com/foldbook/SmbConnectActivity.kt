package com.foldbook

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SmbConnectActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_smb)
        title = getString(R.string.open_smb)

        val prefs = Prefs(this)
        val last = prefs.smb
        val host = bind(R.id.smbHost, last.host)
        val share = bind(R.id.smbShare, last.share)
        val path = bind(R.id.smbPath, last.path)
        val user = bind(R.id.smbUser, last.user)
        val pass = bind(R.id.smbPass, last.pass)
        val domain = bind(R.id.smbDomain, last.domain)

        findViewById<Button>(R.id.smbConnect).setOnClickListener {
            val s = Source.Smb(
                host = host.text.toString().trim(),
                share = share.text.toString().trim(),
                path = path.text.toString().trim(),
                user = user.text.toString().trim(),
                pass = pass.text.toString(),
                domain = domain.text.toString().trim(),
            )
            if (s.host.isBlank() || s.share.isBlank()) {
                Toast.makeText(this, R.string.smb_need_host_share, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.smb = s
            startActivity(
                Intent(this, ReaderActivity::class.java)
                    .putExtra(ReaderActivity.EXTRA_SOURCE, s.toJson())
            )
        }
    }

    private fun bind(id: Int, value: String): EditText =
        findViewById<EditText>(id).apply { setText(value) }
}
