package com.petcamel

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.petcamel.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var stateManager: CamelStateManager
    private val refreshHandler = Handler(Looper.getMainLooper())

    private val refreshRunnable = object : Runnable {
        override fun run() {
            binding.gameView.state = stateManager.load()
            refreshHandler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        stateManager = CamelStateManager(this)
        val state = stateManager.load()
        binding.gameView.state = state

        if (!state.isNamed) {
            showNameDialog()
        }

        binding.gameView.onPet = {
            handlePet()
        }

        binding.btnFeed.setOnClickListener {
            handleFeed()
        }

        binding.btnPet.setOnClickListener {
            handlePet()
        }

        binding.btnRename.setOnClickListener {
            showNameDialog()
        }
    }

    private fun handleFeed() {
        val newState = stateManager.load().withFeed()
        stateManager.save(newState)
        binding.gameView.state = newState
        binding.gameView.triggerFeedAnimation()
        showLoveToast(newState, "Nom nom! +${CamelState.FEED_BOOST.toInt()}")
    }

    private fun handlePet() {
        val newState = stateManager.load().withPet()
        stateManager.save(newState)
        binding.gameView.state = newState
        binding.gameView.triggerHeartAnimation()
        showLoveToast(newState, "Purr... +${CamelState.PET_BOOST.toInt()}")
    }

    private fun showLoveToast(state: CamelState, action: String) {
        val name = if (state.name.isNotBlank()) state.name else "Your camel"
        Toast.makeText(this, "$name loves you! $action", Toast.LENGTH_SHORT).show()
    }

    private fun showNameDialog() {
        val input = EditText(this).apply {
            hint = "Enter a name..."
            maxLines = 1
            setPadding(48, 32, 48, 32)
        }

        val current = stateManager.load()
        if (current.isNamed) input.setText(current.name)

        AlertDialog.Builder(this)
            .setTitle("Name your camel")
            .setMessage("What shall you call your companion?")
            .setView(input)
            .setPositiveButton("Confirm") { _, _ ->
                val name = input.text.toString().trim().take(20)
                if (name.isNotEmpty()) {
                    val newState = stateManager.load().withName(name)
                    stateManager.save(newState)
                    binding.gameView.state = newState
                    Toast.makeText(this, "Welcome, $name!", Toast.LENGTH_SHORT).show()
                }
            }
            .setCancelable(current.isNamed)
            .show()
    }

    override fun onResume() {
        super.onResume()
        binding.gameView.state = stateManager.load()
        refreshHandler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
    }
}
