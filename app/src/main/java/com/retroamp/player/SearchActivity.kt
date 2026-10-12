
package com.retroamp.player

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class SearchActivity : Activity() {

    private val green = Color.rgb(108, 255, 85)

    private lateinit var results: LinearLayout
    private lateinit var status: TextView
    private lateinit var queryInput: EditText
    private lateinit var searchButton: Button

    // Somente a chave PUBLICA do consumidor.
    // Nunca coloque o consumer secret no APK.
    private val consumerKey = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(14), dp(12), dp(12))
            setBackgroundColor(Color.rgb(25, 25, 28))
        }

        setContentView(root)

        val header = TextView(this).apply {
            text = "◀   RETROAMP • BUSCAR MP3"
            textSize = 17f
            setTextColor(green)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setBackgroundColor(Color.rgb(5, 22, 13))
            setOnClickListener { finish() }
        }

        root.addView(header)

        queryInput = EditText(this).apply {
            hint = "Artista ou nome da música"
            setHintTextColor(Color.LTGRAY)
            setTextColor(Color.WHITE)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }

        root.addView(queryInput)

        searchButton = Button(this).apply {
            text = "🔎 PESQUISAR NO 4SHARED"
            isAllCaps = false
            setTextColor(Color.BLACK)
            backgroundTintList = ColorStateList.valueOf(green)
            setOnClickListener { search() }
        }

        root.addView(searchButton)

        queryInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                search()
                true
            } else {
                false
            }
        }

        status = TextView(this).apply {
            text = "Pesquise arquivos de música no 4shared."
            setTextColor(green)
            textSize = 13f
            setPadding(dp(5), dp(10), dp(5), dp(10))
        }

        root.addView(status)

        val scroll = ScrollView(this)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        results = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        scroll.addView(results)

        root.addView(TextView(this).apply {
            text = "RETROAMP 1.6.0 • 4SHARED"
            setTextColor(Color.LTGRAY)
            textSize = 11f
            gravity = Gravity.CENTER
        })
    }

    private fun search() {
        val query = queryInput.text.toString().trim()

        if (query.isEmpty()) {
            queryInput.error = "Digite o nome da música"
            return
        }

        if (consumerKey.isBlank()) {
            AlertDialog.Builder(this)
                .setTitle("Configuração pendente")
                .setMessage(
                    "A pesquisa precisa da chave pública " +
                    "do aplicativo 4shared. Não coloque " +
                    "o consumer secret neste arquivo."
                )
                .setPositiveButton("Entendi", null)
                .show()
            return
        }

        searchButton.isEnabled = false
        status.text = "Pesquisando..."
        results.removeAllViews()

        Thread {
            var response: String? = null
            var error: String? = null

            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val key = URLEncoder.encode(consumerKey, "UTF-8")

                // Requisição experimental.
                // A autenticação ainda precisa ser validada.
                val endpoint =
                    "https://search.4shared.com/v1_2/files" +
                    "?query=$encoded" +
                    "&category=1" +
                    "&limit=30" +
                    "&offset=0" +
                    "&oauth_consumer_key=$key"

                val connection =
                    URL(endpoint).openConnection() as HttpURLConnection

                try {
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 12000
                    connection.readTimeout = 12000
                    connection.setRequestProperty(
                        "Accept", "application/json"
                    )

                    val code = connection.responseCode

                    val stream = if (code in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }

                    val body = stream
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        .orEmpty()

                    if (code !in 200..299) {
                        throw IllegalStateException(
                            "HTTP $code: ${body.take(180)}"
                        )
                    }

                    response = body

                } finally {
                    connection.disconnect()
                }

            } catch (e: Exception) {
                error = e.message ?: "Falha na conexão"
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    return@runOnUiThread
                }

                searchButton.isEnabled = true

                if (error != null) {
                    status.text = "Falha na pesquisa: $error"
                    return@runOnUiThread
                }

                val body = response.orEmpty()

                try {
                    val json = JSONObject(body)

                    status.text =
                        "Resposta recebida. Formato ainda em teste."

                    results.addView(TextView(this).apply {
                        text = json.toString(2).take(3500)
                        setTextColor(Color.LTGRAY)
                        textSize = 12f
                        setTextIsSelectable(true)
                    })

                } catch (_: Exception) {
                    status.text =
                        "Resposta em formato ainda não reconhecido."

                    results.addView(TextView(this).apply {
                        text = body.take(1500)
                        setTextColor(Color.LTGRAY)
                        setTextIsSelectable(true)
                    })
                }
            }
        }.start()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
