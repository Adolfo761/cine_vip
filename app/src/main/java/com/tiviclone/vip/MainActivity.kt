package com.tiviclone.vip

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.ui.PlayerView
import okhttp3.OkHttpClient
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

data class Channel(val name: String, val url: String, val group: String, val logo: String)

class MainActivity : AppCompatActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var rvGroups: RecyclerView
    private lateinit var rvChannels: RecyclerView
    private lateinit var tvStatus: TextView
    private lateinit var uiContainer: View
    private lateinit var infoContainer: View
    private lateinit var etSearch: EditText
    
    // URL FIJA
    private val PLAYLIST_URL = "https://tinyurl.com/yuwjrwbx"
    private val CACHE_FILE = "playlist_v2.m3u"

    private val allChannels = mutableListOf<Channel>()
    private val groups = mutableListOf<String>()
    private val channelsInCurrentGroup = mutableListOf<Channel>()
    private var currentGroupSelection = ""
    private var currentPlayingChannel: Channel? = null
    
    private var lastBackPressTime: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)

            playerView = findViewById(R.id.playerView)
            rvGroups = findViewById(R.id.rvGroups)
            rvChannels = findViewById(R.id.rvChannels)
            tvStatus = findViewById(R.id.tvStatus)
            uiContainer = findViewById(R.id.uiContainer)
            infoContainer = findViewById(R.id.infoContainer)
            etSearch = findViewById(R.id.etSearch)

            setupPlayer()

            rvGroups.layoutManager = LinearLayoutManager(this)
            rvChannels.layoutManager = GridLayoutManager(this, 3)

            etSearch.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { filterChannels(s.toString()) }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })

            // Click en pantalla -> Muestra menú
            playerView.setOnClickListener { showUI() }

            // INICIAR CARGA INTELIGENTE
            loadContentSmart()
            
        } catch (e: Exception) {
            Toast.makeText(this, "Error al iniciar: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadContentSmart() {
        val file = File(filesDir, CACHE_FILE)
        if (file.exists() && file.length() > 0) {
            // CARGA RAPIDA (CACHE)
            tvStatus.text = "Cargando..."
            thread { parsePlaylist(file) }
        } else {
            // PRIMERA VEZ (DESCARGA)
            tvStatus.text = "Descargando contenido (Espere)..."
            thread { downloadPlaylist(file) }
        }
    }

    private fun downloadPlaylist(targetFile: File) {
        try {
            val url = URL(PLAYLIST_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15000
            
            val content = conn.inputStream.bufferedReader().use { it.readText() }
            targetFile.writeText(content)
            
            parsePlaylist(targetFile)
        } catch (e: Exception) {
            runOnUiThread { 
                tvStatus.text = "Error de Red"
                Toast.makeText(this, "Verifique su internet", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun parsePlaylist(file: File) {
        try {
            val reader = BufferedReader(FileReader(file))
            var line = reader.readLine()
            var cName = "Sin Nombre"
            var cGroup = "GENERAL"
            var cLogo = ""
            
            allChannels.clear()
            
            while (line != null) {
                val trimmed = line.trim()
                if (trimmed.startsWith("#EXTINF")) {
                    val parts = trimmed.split(",")
                    if (parts.size > 1) cName = parts.last().trim()
                    
                    val groupMatch = "group-title=\"([^\"]*)\"".toRegex().find(trimmed)
                    cGroup = groupMatch?.groupValues?.get(1)?.trim() ?: "GENERAL"
                    
                    val logoMatch = "tvg-logo=\"([^\"]*)\"".toRegex().find(trimmed)
                    cLogo = logoMatch?.groupValues?.get(1)?.trim() ?: ""
                    
                } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    allChannels.add(Channel(cName, trimmed, cGroup, cLogo))
                    cLogo = ""
                }
                line = reader.readLine()
            }
            reader.close()
            
            runOnUiThread { 
                setupGroups()
                tvStatus.text = ""
                // Mostrar menú al inicio
                showUI()
            }
        } catch (e: Exception) {
             runOnUiThread { tvStatus.text = "Error de Datos" }
        }
    }

    // --- LOGICA BOTON ATRAS ---
    override fun onBackPressed() {
        if (uiContainer.visibility == View.VISIBLE) {
            // EL MENÚ ESTÁ ABIERTO
            if (player.isPlaying) {
                // 1 Clic -> Volver al Video
                hideUI()
            } else {
                // 2 Clics -> Salir
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastBackPressTime < 2000) {
                    super.onBackPressed()
                    finishAffinity()
                } else {
                    lastBackPressTime = currentTime
                    Toast.makeText(this, "Presiona otra vez para SALIR", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            // ESTAMOS VIENDO VIDEO -> ABRIR MENU
            showUI()
        }
    }

    // --- CONTROL REMOTO (ZAPPING) ---
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (uiContainer.visibility == View.GONE) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU -> {
                    showUI()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    zapChannel(-1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    zapChannel(1)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun zapChannel(offset: Int) {
        if (channelsInCurrentGroup.isEmpty()) { showUI(); return }
        val idx = channelsInCurrentGroup.indexOfFirst { it.url == currentPlayingChannel?.url }
        if (idx != -1) {
            val size = channelsInCurrentGroup.size
            val newIdx = (idx + offset + size) % size
            playChannel(channelsInCurrentGroup[newIdx])
        }
    }

    private fun showUI() {
        uiContainer.visibility = View.VISIBLE
        playerView.useController = true
        playerView.showController()
        if (!rvGroups.hasFocus()) rvGroups.requestFocus()
    }

    private fun hideUI() {
        if (!etSearch.hasFocus()) {
            uiContainer.visibility = View.GONE
            playerView.hideController()
        }
    }

    // --- REPRODUCTOR ---
    private fun setupPlayer() {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
        })
        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, SecureRandom())
        val client = OkHttpClient.Builder().sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager).hostnameVerifier { _, _ -> true }.build()
        val dataSourceFactory = OkHttpDataSource.Factory(client).setUserAgent("CineVIP/2.0")

        player = ExoPlayer.Builder(this).setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)).build()
        playerView.player = player
        
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_BUFFERING) {
                     tvStatus.text = "Cargando..."
                     infoContainer.visibility = View.VISIBLE
                }
                if (state == Player.STATE_READY) {
                    tvStatus.text = ""
                    infoContainer.postDelayed({ infoContainer.visibility = View.GONE }, 2000)
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                tvStatus.text = "Error de Video"
                infoContainer.visibility = View.VISIBLE
                showUI()
            }
        })
    }

    // --- ADAPTERS ---
    private fun setupGroups() {
        groups.clear()
        val unique = allChannels.map { it.group }.distinct().sorted()
        groups.addAll(unique)
        rvGroups.adapter = SimpleAdapter(groups) { group -> 
            currentGroupSelection = group
            showChannelsForGroup(group)
        }
        if (groups.isNotEmpty()) {
             currentGroupSelection = groups[0]
             showChannelsForGroup(groups[0])
        }
    }

    private fun showChannelsForGroup(group: String) {
        channelsInCurrentGroup.clear()
        channelsInCurrentGroup.addAll(allChannels.filter { it.group == group })
        rvChannels.adapter = ChannelAdapter(channelsInCurrentGroup) { ch -> playChannel(ch) }
    }

    private fun playChannel(channel: Channel) {
        currentPlayingChannel = channel
        hideUI() // OCULTAR MENU AL SELECCIONAR
        infoContainer.visibility = View.VISIBLE
        // Usamos solo el nombre en el toast/status
        tvStatus.text = "Conectando..."
        
        try {
            player.stop()
            player.clearMediaItems()
            player.setMediaItem(MediaItem.fromUri(channel.url))
            player.prepare()
            player.play()
        } catch(e: Exception) { showUI() }
    }

    // Adapter Grupos
    inner class SimpleAdapter(private val items: List<String>, private val onClick: (String) -> Unit) : RecyclerView.Adapter<SimpleAdapter.ViewHolder>() {
        private var selectedPos = -1
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) { val t: TextView = v.findViewById(R.id.text1) }
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = ViewHolder(layoutInflater.inflate(R.layout.item_list, p, false))
        override fun onBindViewHolder(h: ViewHolder, i: Int) {
            h.t.text = items[i]
            h.itemView.isSelected = (selectedPos == i)
            h.itemView.setOnClickListener { selectedPos = h.adapterPosition; notifyDataSetChanged(); onClick(items[i]) }
        }
        override fun getItemCount() = items.size
    }

    // Adapter Canales (Con Glide)
    inner class ChannelAdapter(private val items: List<Channel>, private val onClick: (Channel) -> Unit) : RecyclerView.Adapter<ChannelAdapter.ViewHolder>() {
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) { 
            val t: TextView = v.findViewById(R.id.text1)
            val img: ImageView = v.findViewById(R.id.imgLogo)
        }
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = ViewHolder(layoutInflater.inflate(R.layout.item_movie_card, p, false))
        override fun onBindViewHolder(h: ViewHolder, i: Int) {
            val c = items[i]
            h.t.text = c.name
            
            if (c.logo.isNotEmpty()) {
                Glide.with(h.itemView.context)
                    .load(c.logo)
                    .placeholder(android.R.drawable.ic_menu_gallery)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(h.img)
            } else {
                h.img.setImageResource(android.R.drawable.ic_menu_gallery)
            }
            h.itemView.setOnClickListener { onClick(c) }
        }
        override fun getItemCount() = items.size
    }
}
