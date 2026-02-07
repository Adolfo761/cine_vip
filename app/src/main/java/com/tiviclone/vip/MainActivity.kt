package com.tiviclone.vip

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.ui.PlayerView
import okhttp3.OkHttpClient
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

data class Channel(val name: String, val url: String, val group: String)

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
    private var currentPlayingChannel: Channel? = null // Guardamos el canal actual para el Zapping
    
    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideUI() }
    private val infoHideHandler = Handler(Looper.getMainLooper())

    private var lastBackPressTime: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("VIP_Favorites", Context.MODE_PRIVATE)
        
        playerView = findViewById(R.id.playerView)
        rvGroups = findViewById(R.id.rvGroups)
        rvChannels = findViewById(R.id.rvChannels)
        tvChannelName = findViewById(R.id.tvChannelName)
        tvStatus = findViewById(R.id.tvStatus)
        uiContainer = findViewById(R.id.uiContainer)
        infoContainer = findViewById(R.id.infoContainer)
        etSearch = findViewById(R.id.etSearch)

        setupPlayer()

        rvGroups.layoutManager = LinearLayoutManager(this)
        rvChannels.layoutManager = LinearLayoutManager(this)

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { filterChannels(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Tocar la pantalla (touch) sigue abriendo el menú
        playerView.setOnClickListener { showUI() }

        loadPlaylist("https://tinyurl.com/yuwjrwbx")
    }

    override fun onPause() {
        super.onPause()
        if (::player.isInitialized) player.pause()
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing && ::player.isInitialized) {
            player.stop()
            player.release()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::player.isInitialized) player.release()
    }

    override fun onBackPressed() {
        if (uiContainer.visibility == View.VISIBLE) {
            if (etSearch.hasFocus()) {
                rvGroups.requestFocus()
                return
            }
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastBackPressTime < 2000) {
                super.onBackPressed() 
                finishAffinity()
            } else {
                lastBackPressTime = currentTime
                Toast.makeText(this, "Presiona otra vez para SALIR", Toast.LENGTH_SHORT).show()
            }
        } else {
            showUI()
        }
    }

    private fun filterChannels(query: String) {
        if (query.isEmpty()) {
            if (currentGroupSelection.isNotEmpty()) showChannelsForGroup(currentGroupSelection)
            return
        }
        val lower = query.lowercase()
        channelsInCurrentGroup.clear()
        channelsInCurrentGroup.addAll(allChannels.filter { it.name.lowercase().contains(lower) })
        rvChannels.adapter?.notifyDataSetChanged()
    }

    // --- LÓGICA DE CONTROL REMOTO MEJORADA ---
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        resetHideTimer()
        
        // Si el Menú está OCULTO (Pantalla Completa)
        if (uiContainer.visibility == View.GONE) {
            when (keyCode) {
                // OK / ENTER / MENÚ -> Abren la lista de canales
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_MENU -> {
                    showUI()
                    return true
                }
                
                // ARRIBA -> Canal Anterior (Zapping)
                KeyEvent.KEYCODE_DPAD_UP -> {
                    zapChannel(-1)
                    return true
                }

                // ABAJO -> Canal Siguiente (Zapping)
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_CHANNEL_DOWN -> { // Soporte botones CH+ CH-
                    zapChannel(1)
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_UP -> {
                    zapChannel(-1)
                    return true
                }

                // IZQUIERDA / DERECHA -> También abren menú (opcional)
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    showUI()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // Función para cambiar de canal rápido (Zapping)
    private fun zapChannel(offset: Int) {
        if (channelsInCurrentGroup.isEmpty()) {
            showUI() // Si no hay lista cargada, mejor mostramos el menú
            return
        }
        
        // Buscar índice del canal actual
        val currentIndex = channelsInCurrentGroup.indexOfFirst { it.url == currentPlayingChannel?.url }
        
        if (currentIndex != -1) {
            // Calcular nuevo índice (circular: si llega al final, vuelve al principio)
            val size = channelsInCurrentGroup.size
            val newIndex = (currentIndex + offset + size) % size 
            playChannel(channelsInCurrentGroup[newIndex])
        } else {
            // Si por alguna razón el canal actual no está en la lista, reproducimos el primero
            playChannel(channelsInCurrentGroup[0])
        }
    }

    private fun showUI() {
        uiContainer.visibility = View.VISIBLE
        playerView.useController = true
        playerView.showController()
        
        if (!rvGroups.hasFocus() && !rvChannels.hasFocus() && !etSearch.hasFocus()) {
             rvGroups.requestFocus()
        }
        resetHideTimer()
    }

    private fun hideUI() {
        if (!etSearch.hasFocus()) {
            uiContainer.visibility = View.GONE
            playerView.hideController()
        }
    }
    
    private fun resetHideTimer() {
        hideHandler.removeCallbacks(hideRunnable)
        hideHandler.postDelayed(hideRunnable, 4000)
    }

    private fun setupPlayer() {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
        })
        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, SecureRandom())
        
        val client = OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        
        val dataSourceFactory = OkHttpDataSource.Factory(client)
            .setUserAgent("VLC/3.0.18 LibVLC/3.0.18")

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory))
            .build()
        playerView.player = player
        
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> {
                        tvStatus.text = "Cargando..."
                        infoContainer.visibility = View.VISIBLE
                    }
                    Player.STATE_READY -> {
                        tvStatus.text = "En Vivo"
                        infoHideHandler.removeCallbacksAndMessages(null)
                        infoHideHandler.postDelayed({ infoContainer.visibility = View.GONE }, 2000)
                    }
                    Player.STATE_IDLE -> {}
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                tvStatus.text = "Error: ${error.errorCodeName}"
                infoContainer.visibility = View.VISIBLE
                showUI()
            }
        })
    }

    private fun loadPlaylist(playlistUrl: String) {
        thread {
            try {
                runOnUiThread { tvStatus.text = "Actualizando..." } 
                val url = URL(playlistUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                var line = reader.readLine()
                var currentName = "Sin Nombre"
                var currentGroup = "OTROS"
                allChannels.clear()
                
                while (line != null) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("#EXTINF")) {
                        val parts = trimmed.split(",")
                        if (parts.size > 1) currentName = parts.last().trim()
                        val groupMatch = "group-title=\"([^\"]*)\"".toRegex().find(trimmed)
                        currentGroup = groupMatch?.groupValues?.get(1)?.trim() ?: "OTROS"
                    } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                        allChannels.add(Channel(currentName, trimmed, currentGroup))
                    }
                    line = reader.readLine()
                }
                runOnUiThread { setupGroups(); tvStatus.text = "" }
            } catch (e: Exception) { 
                runOnUiThread { tvStatus.text = "Error de Lista" } 
            }
        }
    }

    private fun isFavorite(url: String): Boolean = prefs.getBoolean(url, false)
    
    private fun toggleFavorite(channel: Channel) {
        val current = isFavorite(channel.url)
        prefs.edit().putBoolean(channel.url, !current).apply()
        Toast.makeText(this, if (!current) "Añadido a Favoritos" else "Removido", Toast.LENGTH_SHORT).show()
        setupGroups()
        if (currentGroupSelection == "⭐ FAVORITOS" || currentGroupSelection == channel.group) {
            showChannelsForGroup(currentGroupSelection)
        }
    }

    private fun setupGroups() {
        groups.clear()
        if (allChannels.any { isFavorite(it.url) }) groups.add("⭐ FAVORITOS")
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

        rvGroups.post { 
            rvGroups.requestFocus() 
        }
    }

    private fun showChannelsForGroup(group: String) {
        channelsInCurrentGroup.clear()
        if (group == "⭐ FAVORITOS") {
            channelsInCurrentGroup.addAll(allChannels.filter { isFavorite(it.url) })
        } else {
            channelsInCurrentGroup.addAll(allChannels.filter { it.group == group })
        }
        rvChannels.adapter = ChannelAdapter(channelsInCurrentGroup) { ch, isLongClick -> 
            if (isLongClick) toggleFavorite(ch) else playChannel(ch) 
        }
    }

    private fun playChannel(channel: Channel) {
        currentPlayingChannel = channel // GUARDAMOS EL CANAL ACTUAL
        
        uiContainer.visibility = View.GONE
        hideHandler.removeCallbacks(hideRunnable) 
        playerView.hideController()

        infoContainer.visibility = View.VISIBLE
        tvChannelName.text = channel.name
        tvStatus.text = "Conectando..."
        
        try {
            player.stop()
            player.clearMediaItems()
            player.setMediaItem(MediaItem.fromUri(channel.url))
            player.prepare()
            player.play()
        } catch(e: Exception) { 
            tvStatus.text = "Error"
            showUI() 
        }
    }

    inner class SimpleAdapter(private val items: List<String>, private val onClick: (String) -> Unit) : RecyclerView.Adapter<SimpleAdapter.ViewHolder>() {
        private var selectedPos = RecyclerView.NO_POSITION
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = layoutInflater.inflate(R.layout.item_list, parent, false)
            return ViewHolder(view)
        }
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) { 
            val text: TextView = view.findViewById(R.id.text1) 
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.text.text = items[position]
            holder.itemView.isSelected = (selectedPos == position)
            holder.itemView.setOnClickListener { 
                val previous = selectedPos
                selectedPos = holder.adapterPosition
                notifyItemChanged(previous)
                notifyItemChanged(selectedPos)
                onClick(items[position]) 
            }
        }
        override fun getItemCount() = items.size
    }

    inner class ChannelAdapter(private val items: List<Channel>, private val onAction: (Channel, Boolean) -> Unit) : RecyclerView.Adapter<ChannelAdapter.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = layoutInflater.inflate(R.layout.item_list, parent, false)
            return ViewHolder(view)
        }
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) { val text: TextView = view.findViewById(R.id.text1) }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val channel = items[position]
            holder.text.text = (if (isFavorite(channel.url)) "★ " else "") + channel.name
            holder.itemView.setOnClickListener { onAction(channel, false) }
            holder.itemView.setOnLongClickListener { onAction(channel, true); true }
        }
        override fun getItemCount() = items.size
    }
}
