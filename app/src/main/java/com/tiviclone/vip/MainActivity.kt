package com.tiviclone.vip

import com.tiviclone.vip.R
import androidx.appcompat.app.AppCompatActivity
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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

data class Channel(val name: String, val url: String, val group: String)

class MainActivity : AppCompatActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var rvGroups: RecyclerView
    private lateinit var rvChannels: RecyclerView
    private lateinit var tvChannelName: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvCategoryTitle: TextView
    private lateinit var uiContainer: View
    private lateinit var infoContainer: View
    private lateinit var etSearch: EditText
    private lateinit var ivCenterLogo: ImageView
    private lateinit var prefs: SharedPreferences
    
    private val allChannels = mutableListOf<Channel>()
    private val groups = mutableListOf<String>()
    private val channelsInCurrentGroup = mutableListOf<Channel>()
    private var currentGroupSelection = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("CineVIP_Data", Context.MODE_PRIVATE)
        
        playerView = findViewById(R.id.playerView)
        rvGroups = findViewById(R.id.rvGroups)
        rvChannels = findViewById(R.id.rvChannels)
        tvChannelName = findViewById(R.id.tvChannelName)
        tvStatus = findViewById(R.id.tvStatus)
        tvCategoryTitle = findViewById(R.id.tvCategoryTitle)
        uiContainer = findViewById(R.id.uiContainer)
        infoContainer = findViewById(R.id.infoContainer)
        etSearch = findViewById(R.id.etSearch)
        ivCenterLogo = findViewById(R.id.ivCenterLogo)

        setupPlayer()

        rvChannels.layoutManager = GridLayoutManager(this, 3)
        rvGroups.layoutManager = LinearLayoutManager(this)

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { filterChannels(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // ELIMINADO EL TIMER: La UI se queda fija
        uiContainer.visibility = View.VISIBLE
        loadPlaylistFromAssets()
    }

    private fun loadPlaylistFromAssets() {
        thread {
            try {
                runOnUiThread { tvStatus.text = "Leyendo peliculas..." }
                val am = assets
                val isStream = am.open("playlist.zip")
                val zis = ZipInputStream(isStream)
                var entry = zis.nextEntry
                while (entry != null && (entry.isDirectory || entry.name.contains("__MACOSX"))) {
                    entry = zis.nextEntry
                }
                if (entry != null) {
                    val reader = BufferedReader(InputStreamReader(zis))
                    var line = reader.readLine()
                    var cName = "Desconocido"; var cGroup = "GENERAL"
                    allChannels.clear()
                    while (line != null) {
                        val tr = line.trim()
                        if (tr.startsWith("#EXTINF")) {
                            val commaIndex = tr.lastIndexOf(",")
                            if (commaIndex != -1) cName = tr.substring(commaIndex + 1).trim()
                            val match = "group-title=\"([^\"]*)\"".toRegex().find(tr)
                            cGroup = match?.groupValues?.get(1)?.trim() ?: "GENERAL"
                        } else if (tr.isNotEmpty() && !tr.startsWith("#")) {
                            allChannels.add(Channel(cName, tr, cGroup))
                        }
                        line = reader.readLine()
                    }
                    zis.closeEntry(); zis.close()
                    runOnUiThread { setupGroups(); Toast.makeText(this@MainActivity, "${allChannels.size} Películas Listas", Toast.LENGTH_SHORT).show() }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun setupGroups() {
        groups.clear()
        val unique = allChannels.map { it.group }.distinct().sorted()
        groups.addAll(unique)
        rvGroups.adapter = SimpleAdapter(groups) { showChannelsForGroup(it) }
        if (groups.isNotEmpty()) showChannelsForGroup(groups[0])
    }

    private fun showChannelsForGroup(group: String) {
        currentGroupSelection = group
        channelsInCurrentGroup.clear()
        channelsInCurrentGroup.addAll(allChannels.filter { it.group == group })
        rvChannels.adapter = ChannelAdapter(channelsInCurrentGroup) { ch -> playChannel(ch) }
        tvCategoryTitle.text = group
    }

    private fun playChannel(channel: Channel) {
        // AL DAR CLICK: Ocultamos el catálogo y mostramos el reproductor
        uiContainer.visibility = View.GONE 
        ivCenterLogo.visibility = View.GONE
        playerView.useController = true
        playerView.showController()
        
        infoContainer.visibility = View.VISIBLE
        tvChannelName.text = channel.name
        tvStatus.text = "Cargando..."
        
        try {
            player.stop()
            player.clearMediaItems()
            player.setMediaItem(MediaItem.fromUri(channel.url))
            player.prepare()
            player.play()
        } catch(e: Exception) { 
            Toast.makeText(this, "Error al reproducir", Toast.LENGTH_SHORT).show()
            showUI() // Si falla, volvemos a mostrar la lista
        }
    }

    // BOTON ATRAS: Si reproduce, para y vuelve a la lista. Si está en lista, sale.
    override fun onBackPressed() {
        if (uiContainer.visibility == View.GONE) {
            // Estamos viendo una peli -> Volver al menú
            player.stop()
            showUI()
        } else {
            // Estamos en el menú -> Salir de la app
            super.onBackPressed()
        }
    }

    private fun showUI() {
        uiContainer.visibility = View.VISIBLE
        ivCenterLogo.visibility = View.VISIBLE
        playerView.hideController()
        infoContainer.visibility = View.GONE
    }
    
    // ... Adapters y Player Setup ...
    override fun onPause() { super.onPause(); if (::player.isInitialized) player.pause() }
    override fun onStop() { super.onStop(); if (isFinishing && ::player.isInitialized) { player.stop(); player.release() } }
    override fun onDestroy() { super.onDestroy(); if (::player.isInitialized) player.release() }
    private fun filterChannels(query: String) {
        if (query.isEmpty()) { if (currentGroupSelection.isNotEmpty()) showChannelsForGroup(currentGroupSelection); return }
        channelsInCurrentGroup.clear()
        channelsInCurrentGroup.addAll(allChannels.filter { it.name.lowercase().contains(query.lowercase()) })
        rvChannels.adapter?.notifyDataSetChanged()
        tvCategoryTitle.text = "Resultados: $query"
    }
    private fun setupPlayer() {
        player = ExoPlayer.Builder(this).build()
        playerView.player = player
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_BUFFERING) { tvStatus.text = "Cargando..."; infoContainer.visibility = View.VISIBLE }
                if (state == Player.STATE_READY) { tvStatus.text = ""; infoContainer.visibility = View.GONE }
            }
            override fun onPlayerError(error: PlaybackException) { tvStatus.text = "Error"; infoContainer.visibility = View.VISIBLE; showUI() }
        })
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
    inner class ChannelAdapter(private val items: List<Channel>, private val onClick: (Channel) -> Unit) : RecyclerView.Adapter<ChannelAdapter.ViewHolder>() {
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) { val t: TextView = v.findViewById(R.id.text1) }
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = ViewHolder(layoutInflater.inflate(R.layout.item_movie_card, p, false))
        override fun onBindViewHolder(h: ViewHolder, i: Int) {
            val c = items[i]; h.t.text = c.name
            h.itemView.setOnClickListener { onClick(c) } // CLICK DIRECTO AQUI
        }
        override fun getItemCount() = items.size
    }
}
