package com.petcamel

import android.app.AlertDialog
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.petcamel.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var stateManager: CamelStateManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        stateManager = CamelStateManager(this)

        if (!stateManager.load().isNamed) showNameDialog()
    }

    private fun showNameDialog() {
        val input = EditText(this).apply {
            hint = "Enter a name…"; maxLines = 1; setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Name your camel")
            .setMessage("What shall you call your companion?")
            .setView(input)
            .setPositiveButton("Confirm") { _, _ ->
                val name = input.text.toString().trim().take(20)
                if (name.isNotEmpty()) {
                    val state = stateManager.load().withName(name)
                    stateManager.save(state)
                    binding.gameView.camelState = state
                    Toast.makeText(this, "Welcome, $name!", Toast.LENGTH_SHORT).show()
                }
            }
            .setCancelable(false)
            .show()
    }

    override fun onResume() { super.onResume(); binding.gameView.onResume() }
    override fun onPause()  { super.onPause();  binding.gameView.onPause() }
}
