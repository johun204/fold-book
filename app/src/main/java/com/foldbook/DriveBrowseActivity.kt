package com.foldbook

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 구글 드라이브 폴더 탐색 → 폴더 선택 시 캐시로 받아 뷰어를 연다.
 * 사전 준비: Google Cloud Console 에서 Drive API 활성화 + OAuth 동의화면(테스트 사용자에 본인 계정) +
 *           OAuth 클라이언트 ID(Android, 패키지 com.foldbook + 서명 SHA-1) 등록. (README 참고)
 */
class DriveBrowseActivity : AppCompatActivity() {

    private val stack = ArrayDeque<Pair<String, String>>() // (folderId, name)
    private var folders: List<Pair<String, String>> = emptyList()
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var pathText: TextView
    private lateinit var signInBtn: Button
    private lateinit var upBtn: Button
    private lateinit var openHereBtn: Button

    private val signIn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        try {
            GoogleSignIn.getSignedInAccountFromIntent(res.data).getResult(ApiException::class.java)
            refreshAuthUi()
            loadCurrent()
        } catch (e: ApiException) {
            Toast.makeText(this, getString(R.string.drive_sign_in_failed, e.statusCode), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_drive)
        title = getString(R.string.open_drive)

        pathText = findViewById(R.id.pathText)
        signInBtn = findViewById(R.id.signInBtn)
        upBtn = findViewById(R.id.upBtn)
        openHereBtn = findViewById(R.id.openHereBtn)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        findViewById<ListView>(R.id.list).apply {
            adapter = this@DriveBrowseActivity.adapter
            setOnItemClickListener { _, _, pos, _ ->
                folders.getOrNull(pos)?.let { stack.addLast(it); loadCurrent() }
            }
        }

        signInBtn.setOnClickListener { launchSignIn() }
        upBtn.setOnClickListener { if (stack.size > 1) { stack.removeLast(); loadCurrent() } }
        openHereBtn.setOnClickListener {
            val cur = stack.last()
            startActivity(
                Intent(this, ReaderActivity::class.java)
                    .putExtra(ReaderActivity.EXTRA_SOURCE, Source.Drive(cur.first, cur.second).toJson())
            )
        }

        stack.addLast("root" to getString(R.string.drive_root))
        refreshAuthUi()
        if (signedIn()) loadCurrent()
    }

    private fun signedIn() = GoogleSignIn.getLastSignedInAccount(this) != null

    private fun refreshAuthUi() {
        signInBtn.visibility = if (signedIn()) View.GONE else View.VISIBLE
        openHereBtn.isEnabled = signedIn()
        upBtn.isEnabled = signedIn()
    }

    private fun launchSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DRIVE_SCOPE))
            .build()
        signIn.launch(GoogleSignIn.getClient(this, gso).signInIntent)
    }

    private fun loadCurrent() {
        pathText.text = stack.joinToString(" / ") { it.second }
        lifecycleScope.launch {
            try {
                val token = DriveAuth.token(applicationContext)
                val list = withContext(Dispatchers.IO) { DriveRepo.listFolders(token, stack.last().first) }
                folders = list
                adapter.clear()
                adapter.addAll(list.map { it.second })
                adapter.notifyDataSetChanged()
            } catch (e: Exception) {
                Toast.makeText(this@DriveBrowseActivity,
                    getString(R.string.drive_load_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }
}
