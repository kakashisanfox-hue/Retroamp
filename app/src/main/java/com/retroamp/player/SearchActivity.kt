
package com.retroamp.player

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class SearchActivity : Activity() {

    private val green = Color.rgb(108, 255, 85)
    private val silver = Color.rgb(207, 207, 213)

    // Chave pública da aplicação 4shared.
    // Substitua quando renovar suas credenciais.
    // NUNCA coloque o Consumer Secret aqui.
    private val consumerKey =
        "05feb5fcdeff443910b846fa803ee1ae"

    private lateinit var queryInput: EditText
    private lateinit var searchButton: Button
    private lateinit var status: TextView
    private lateinit var results: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(25, 25, 28))
            setPadding(dp(12), dp(12), dp(12), dp(8))
        }

        setContentView(root)

        val header = TextView(this).apply {
            text = "◀  RETROAMP • BUSCAR MP3"
            textSize = 17f
            setTextColor(green)
            typeface = Typeface.MONOSPACE
            setPadding(dp(8), dp(14), dp(8), dp(14))
            setBackgroundColor(Color.rgb(5, 22, 13))
            setOnClickListener { finish() }
        }

        root.addView(header)

        queryInput = EditText(this).apply {
            hint = "Artista ou nome da música"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            textSize = 15f
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
            text = "Pesquise músicas compartilhadas no 4shared."
            textSize = 13f
            setTextColor(green)
            setPadding(dp(4), dp(12), dp(4), dp(12))
        }

        root.addView(status)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(11, 19, 13))
        }

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
            setTextColor(silver)
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(8), dp(4), dp(4))
        })
    }

    private fun search() {
        val query = queryInput.text.toString().trim()

        if (query.isEmpty()) {
            queryInput.error = "Digite uma música ou artista"
            return
        }

        searchButton.isEnabled = false
        results.removeAllViews()
        status.text = "Pesquisando no 4shared..."

        Thread {
            var response: String? = null
            var error: String? = null

            try {
                val encodedQuery =
                    URLEncoder.encode(query, "UTF-8")

                val encodedKey =
                    URLEncoder.encode(consumerKey, "UTF-8")

                // Requisição experimental baseada
                // na documentação da API pública.
                val address =
                    "https://search.4shared.com/v1_2/files" +
                    "?query=$encodedQuery" +
                    "&category=1" +
                    "&limit=30" +
                    "&offset=0" +
                    "&oauth_consumer_key=$encodedKey"

                val connection =
                    URL(address).openConnection()
                        as HttpURLConnection

                try {
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 15000
                    connection.readTimeout = 15000

                    connection.setRequestProperty(
                        "Accept",
                        "application/json"
                    )

                    val code = connection.responseCode

                    val stream =
                        if (code in 200..299) {
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
                            "HTTP $code: ${body.take(200)}"
                        )
                    }

                    response = body

                } finally {
                    connection.disconnect()
                }

            } catch (e: Exception) {
                error = e.message ?: "Erro de conexão"
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    return@runOnUiThread
                }

                searchButton.isEnabled = true

                if (error != null) {
                    status.text = "Erro na pesquisa: $error"
                    return@runOnUiThread
                }

                showResults(response.orEmpty())
            }
        }.start()
    }

    private fun showResults(body: String) {
        results.removeAllViews()

        try {
            val json = JSONObject(body)

            // A documentação chama a coleção
            // de FileListResponse.
            // O nome exato da propriedade
            // precisa ser confirmado em teste.
            val files =
                json.optJSONArray("files")
                    ?: json.optJSONArray("arquivos")
                    ?: JSONArray()

            if (files.length() == 0) {
                status.text =
                    "Nenhum arquivo encontrado ou formato inesperado."

                results.addView(
                    makeLabel(
                        "Resposta da API:\n${body.take(1200)}",
                        silver,
                        12f
                    )
                )
                return
            }

            status.text =
                "${files.length()} resultados encontrados"

            for (i in 0 until files.length()) {
                val item = files.optJSONObject(i)
                    ?: continue

                val name =
                    item.optString("name").ifBlank {
                        item.optString("nome", "Arquivo")
                    }

                val size =
                    item.optLong(
                        "size",
                        item.optLong("tamanho", 0)
                    )

                val page =
                    item.optString("downloadPage")
                        .ifBlank {
                            item.optString("downloadPageUrl")
                        }
                        .ifBlank {
                            item.optString("downloadUrl")
                        }
                        .ifBlank {
                            item.optString("download_page")
                        }

                addResult(name, size, page)
            }

        } catch (e: Exception) {
            status.text =
                "A API respondeu, mas o formato não foi reconhecido."

            results.addView(
                makeLabel(
                    body.take(1500),
                    silver,
                    12f
                )
            )
        }
    }

    private fun addResult(
        name: String,
        size: Long,
        page: String
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(Color.rgb(28, 44, 33))
        }

        val nameLabel = makeLabel(
            "♫ $name",
            green,
            14f
        ).apply {
            maxLines = 3
            typeface = Typeface.MONOSPACE
        }

        card.addView(nameLabel)

        if (size > 0) {
            val mb = size / (1024.0 * 1024.0)

            card.addView(
                makeLabel(
                    String.format(
                        java.util.Locale.US,
                        "Tamanho: %.2f MB",
                        mb
                    ),
                    silver,
                    12f
                )
            )
        }

        val downloadButton = Button(this).apply {
            text = "⬇ BAIXAR"
            isAllCaps = false
            setTextColor(green)

            setOnClickListener {
                showDownloadDialog(page)
            }
        }

        card.addView(downloadButton)

        results.addView(
            card,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(8)
            }
        )
    }

    private fun showDownloadDialog(page: String) {
        AlertDialog.Builder(this)
            .setTitle("Download pelo 4shared")
            .setMessage(
                "Para baixar arquivos, talvez seja " +
                "necessário entrar ou criar uma conta " +
                "no 4shared.\n\n" +
                "O download será realizado pelo site " +
                "oficial, conforme as permissões do arquivo."
            )
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("CONTINUAR") { _, _ ->

                val address = if (
                    page.startsWith("https://")
                ) {
                    page
                } else {
                    "https://www.4shared.com/"
                }

                try {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(address)
                        )
                    )
                } catch (_: Exception) {
                    Toast.makeText(
                        this,
                        "Não foi possível abrir o 4shared",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .show()
    }

    private fun makeLabel(
        value: String,
        color: Int,
        size: Float
    ) = TextView(this).apply {
        text = value
        setTextColor(color)
        textSize = size
        setPadding(dp(4), dp(5), dp(4), dp(5))
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
