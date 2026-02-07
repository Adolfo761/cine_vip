package com.tiviclone.vip

import android.content.Context
import android.content.SharedPreferences
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
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

// Agregamos campo 'logo' al canal
data class Channel(val name: String, val url: String, val group: String, val logo: String)

class MainActivity : AppCompatActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var rvGroups: RecyclerView
    private lateinit var rvChannels: RecyclerView
    private lateinit var tvChannelName: TextView
    private lateinit var tvStatus: TextView
    private lateinit var uiContainer: View
    private lateinit var infoContainer: View
    private lateinit var etSearch: EditText
    private lateinit var prefs: SharedPreferences

    private val allChannels = mutableListOf<Channel>()
    private val groups = mutableListOf<String>()
    private val channelsInCurrentGroup = mutableListOf<Channel>()
    private var currentGroupSelection = ""
    private var currentPlayingChannel: Channel? = null
    
    // CACHÉ: Nombre del archivo local
    private val CACHE_FILE_NAME = "playlist_cache_v1.m3u"
    
    private var lastBackPressTime: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("VIP_Data", Context.MODE_PRIVATE)
        
        playerView = findViewById(R.id.playerView)
        rvGroups = findViewById(R.id.rvGroups)
        rvChannels = findViewById(R.id.rvChannels)
        tvChannelName = findViewById(R.id.tvChannelName)
        tvStatus = findViewById(R.id.tvStatus)
        uiContainer = findViewById(R.id.uiContainer)
        infoContainer = findViewById(R.id.infoContainer)
        etSearch = findViewById(R.id.etSearch)

        setupPlayer()

        // Usamos Grid para que se vean bonitas las tarjetas
        rvGroups.layoutManager = LinearLayoutManager(this)
        rvChannels.layoutManager = GridLayoutManager(this, 3)

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { filterChannels(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Click en pantalla muestra menú
        playerView.setOnClickListener { showUI() }

        // CARGA INTELIGENTE
        checkAndLoadPlaylist()
    }

    // --- LÓGICA DE CACHÉ ---
    private fun checkAndLoadPlaylist() {
        val cacheFile = File(filesDir, CACHE_FILE_NAME)
        
        if (cacheFile.exists() && cacheFile.length() > 0) {
            // SI YA EXISTE: Carga instantánea desde el celular
            tvStatus.text = "Cargando biblioteca..."
            thread {
                parsePlaylistFile(cacheFile)
            }
        } else {
            // PRIMERA VEZ: Descarga de internet
            tvStatus.text = "Descargando contenido (Solo la primera vez)..."
            loadPlaylistFromNetwork("https://tinyurl.com/yuwjrwbx", cacheFile)
        }
    }

    private fun loadPlaylistFromNetwork(playlistUrl: String, saveToFile: File) {
        thread {
            try {
                val url = URL(playlistUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                
                // Leemos y guardamos al mismo tiempo
                val inputStream = conn.inputStream
                val content = inputStream.bufferedReader().use { it.readText() }
                
                // Guardar en disco para la próxima
                saveToFile.writeText(content)
                
                // Ahora procesamos lo guardado
                parsePlaylistFile(saveToFile)
                
            } catch (e: Exception) { 
                runOnUiThread { tvStatus.text = "Error de descarga: Verifique internet" } 
            }
        }
    }

    private fun parsePlaylistFile(file: File) {
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
                    // Extraer Nombre
                    val parts = trimmed.split(",")
                    if (parts.size > 1) cName = parts.last().trim()
                    
                    // Extraer Grupo
                    val groupMatch = "group-title=\"([^\"]*)\"".toRegex().find(trimmed)
                    cGroup = groupMatch?.groupValues?.get(1)?.trim() ?: "GENERAL"
                    
                    // Extraer Logo (tvg-logo)
                    val logoMatch = "tvg-logo=\"([^\"]*)\"".toRegex().find(trimmed)
                    cLogo = logoMatch?.groupValues?.get(1)?.trim() ?: ""
                    
                } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    allChannels.add(Channel(cName, trimmed, cGroup, cLogo))
                    cLogo = "" // Reset logo
                }
                line = reader.readLine()
            }
            reader.close()
            
            runOnUiThread { 
                setupGroups()
                tvStatus.text = "" 
                // Si no hay nada reproduciendo, mostrar menú
                if (!player.isPlaying) showUI()
            }
        } catch (e: Exception) {
            runOnUiThread { tvStatus.text = "Error al procesar lista" }
        }
    }

    // --- LOGICA DE BOTON ATRAS ---
    override fun onBackPressed() {
        if (uiContainer.visibility == View.VISIBLE) {
            // EL MENU ESTA ABIERTO
            if (player.isPlaying) {
                // Si hay video de fondo -> VOLVER AL VIDEO
                hideUI()
            } else {
                // Si no hay video -> DOBLE CLICK PARA SALIR
                if (etSearch.hasFocus()) { rvGroups.requestFocus(); return }
                
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
            // EL VIDEO ESTA SONANDO -> MOSTRAR MENU
            showUI()
        }
    }

    // --- LOGICA ZAPPING (Control Remoto) ---
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (uiContainer.visibility == View.GONE) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU -> { showUI(); return true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { zapChannel(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { zapChannel(1); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun zapChannel(offset: Int) {
        if (channelsInCurrentGroup.isEmpty()) { showUI(); return }
        val currentIndex = channelsInCurrentGroup.indexOfFirst { it.url == currentPlayingChannel?.url }
        if (currentIndex != -1) {
            val size = channelsInCurrentGroup.size
            val newIndex = (currentIndex + offset + size) % size 
            playChannel(channelsInCurrentGroup[newIndex])
        }
    }

    private fun showUI() {
        uiContainer.visibility = View.VISIBLE
        playerView.useController = true
        playerView.showController()
        if (!rvGroups.hasFocus()) rvGroups.requestFocus()
        // NOTA: YA NO HAY TIMER. El menu se queda quieto.
    }

    private fun hideUI() {
        if (!etSearch.hasFocus()) {
            uiContainer.visibility = View.GONE
            playerView.hideController()
        }
    }

    // ... (SetupPlayer y otros métodos auxiliares se mantienen igual, solo actualizamos los adapters) ...

    private fun setupPlayer() {
        // (Configuración SSL igual que antes)
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
        })
        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, SecureRandom())
        val client = OkHttpClient.Builder().sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager).hostnameVerifier { _, _ -> true }.build()
        val dataSourceFactory = OkHttpDataSource.Factory(client).setUserAgent("VLC/3.0.18 LibVLC/3.0.18")
        player = ExoPlayer.Builder(this).setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)).build()
        playerView.player = player
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_BUFFERING) { tvStatus.text = "Cargando..."; infoContainer.visibility = View.VISIBLE }
                if (state == Player.STATE_READY) { 
                    tvStatus.text = "Reproduciendo"
                    infoContainer.postDelayed({ infoContainer.visibility = View.GONE }, 2000)
                }
            }
            override fun onPlayerError(error: PlaybackException) { tvStatus.text = "Error"; infoContainer.visibility = View.VISIBLE; showUI() }
        })
    }
    
    // --- ADAPTERS ACTUALIZADOS CON IMAGENES ---
    
    private fun setupGroups() {
        groups.clear()
        val uniqueGroups = allChannels.map { it.group }.distinct().sorted()
        groups.addAll(uniqueGroups)
        rvGroups.adapter = SimpleAdapter(groups) { group -> 
            currentGroupSelection = group
            showChannelsForGroup(group)
        }
        if (groups.isNotEmpty() && currentGroupSelection.isEmpty()) { 
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
        hideUI() // Escondemos menu inmediatamente al dar click
        infoContainer.visibility = View.VISIBLE
        tvChannelName.text = channel.name
        
        try {
            player.stop()
            player.clearMediaItems()
            player.setMediaItem(MediaItem.fromUri(channel.url))
            player.prepare()
            player.play()
        } catch(e: Exception) { showUI() }
    }

    inner class SimpleAdapter(private val items: List<String>, private val onClick: (String) -> Unit) : RecyclerView.Adapter<SimpleAdapter.ViewHolder>() {
        private var selectedPos = -1
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) { val t: TextView = v.findViewById(R.id.text1) }
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = ViewHolder(layoutInflater.inflate(R.layout.item_list, p, false))
        override fun onBindViewHolder(h: ViewHolder, i: Int) {
            h.t.text = items[i]; h.itemView.isSelected = (selectedPos == i)
            h.itemView.setOnClickListener { selectedPos = h.adapterPosition; notifyDataSetChanged(); onClick(items[i]) }
        }
        override fun getItemCount() = items.size
    }

    // ADAPTER CON GLIDE (IMAGENES)
    inner class ChannelAdapter(private val items: List<Channel>, private val onClick: (Channel) -> Unit) : RecyclerView.Adapter<ChannelAdapter.ViewHolder>() {
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) { 
            val t: TextView = v.findViewById(R.id.text1) 
            val img: ImageView = v.findViewById(R.id.imgLogo)
        }
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = ViewHolder(layoutInflater.inflate(R.layout.item_movie_card, p, false))
        
        override fun onBindViewHolder(h: ViewHolder, i: Int) {
            val c = items[i]
            h.t.text = c.name
            
            // Cargar imagen si existe
            if (c.logo.isNotEmpty()) {
                Glide.with(h.itemView.context)
                    .load(c.logo)
                    .diskCacheStrategy(DiskCacheStrategy.ALL) // Cachear imagenes tambien
                    .placeholder(android.R.drawable.ic_menu_gallery)
                    .into(h.img)
            } else {
                h.img.setImageResource(android.R.drawable.ic_menu_gallery)
            }
            
            h.itemView.setOnClickListener { onClick(c) }
        }
        override fun getItemCount() = items.size
    }
    
    // Lifecycle
    override fun onPause() { super.onPause(); if (::player.isInitialized) player.pause() }
    override fun onStop() { super.onStop(); if (isFinishing && ::player.isInitialized) { player.stop(); player.release() } }
    override fun onDestroy() { super.onDestroy(); if (::player.isInitialized) player.release() }
    private fun filterChannels(query: String) {
        if (query.isEmpty()) { if (currentGroupSelection.isNotEmpty()) showChannelsForGroup(currentGroupSelection); return }
        channelsInCurrentGroup.clear()
        channelsInCurrentGroup.addAll(allChannels.filter { it.name.lowercase().contains(query.lowercase()) })
        rvChannels.adapter?.notifyDataSetChanged()
    }
}
