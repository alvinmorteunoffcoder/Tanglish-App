package com.example

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.AppDatabase
import com.example.data.UserWord
import com.example.data.WordRepository
import com.example.engine.TamilToTanglishTransliterator
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background // 0xFF1A1C1E Slate back
                ) { innerPadding ->
                    DashboardScreen(
                        modifier = Modifier.padding(innerPadding),
                        onEnableKeyboard = { openKeyboardSettings() },
                        onSelectKeyboard = { showInputMethodPickerSelector() }
                    )
                }
            }
        }
    }

    private fun openKeyboardSettings() {
        try {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            Toast.makeText(this, "Enable 'Tanglish Keyboard' in settings", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to open settings: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showInputMethodPickerSelector() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        if (imm != null) {
            imm.showInputMethodPicker()
        } else {
            Toast.makeText(this, "Input method picker not available.", Toast.LENGTH_SHORT).show()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    modifier: Modifier = Modifier,
    onEnableKeyboard: () -> Unit,
    onSelectKeyboard: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // Room Repository reference
    val db = remember { AppDatabase.getDatabase(context) }
    val repository = remember { WordRepository(db.userWordDao()) }
    
    // Observe Custom Word Dictionary from Flow
    val customWords by repository.allWordsFlow.collectAsState(initial = emptyList())
    
    // User configuration preferences
    val prefs = remember { context.getSharedPreferences("tanglish_keyboard_prefs", Context.MODE_PRIVATE) }
    var selectedProfile by remember { mutableStateOf(prefs.getString("battery_profile", "Balanced") ?: "Balanced") }
    var layoutQwertz by remember { mutableStateOf(prefs.getBoolean("layout_qwertz", false)) }
    
    // Add custom word modal/input states
    var isAddingWord by remember { mutableStateOf(false) }
    var tamilInput by remember { mutableStateOf("") }
    var tanglishInput by remember { mutableStateOf("") }
    
    // Test playground state
    var playgroundText by remember { mutableStateOf("") }

    // Helper state variables checking system-wide setups
    var isKeyboardEnabled by remember { mutableStateOf(false) }
    var isKeyboardSelected by remember { mutableStateOf(false) }

    // Speech permissions state check
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, 
                android.Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasMicPermission = isGranted
        if (isGranted) {
            Toast.makeText(context, "Microphone access enabled for keyboard voice input!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Microphone access denied. Tanglish keyboard will use standard keys.", Toast.LENGTH_LONG).show()
        }
    }

    // High fidelity dynamic polling/observer on Resume to capture settings and selector updates immediately
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                try {
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    val enabledList = imm?.enabledInputMethodList ?: emptyList()
                    val pkg = context.packageName
                    val isEnabled = enabledList.any { it.packageName == pkg }
                    
                    val currentIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                    val isSelected = currentIme != null && currentIme.contains(pkg)
                    
                    val hasMic = ContextCompat.checkSelfPermission(
                        context, 
                        android.Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    
                    isKeyboardEnabled = isEnabled
                    isKeyboardSelected = isSelected
                    hasMicPermission = hasMic
                } catch (e: Throwable) {
                    // Safe fallback in case of system restrictions/SecurityException
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("dashboard_scroll"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Header styled with Geometric Balance details
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Tanglish Keyboard",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFD1E4FF), // Mapped english blue accent
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("dashboard_title")
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "High-Fidelity Offline English-Tamil Transliteration",
                        fontSize = 12.sp,
                        color = Color(0xFF909094), // GeoTextSecondary
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Active Status Setup Wizard Card with #2D3033 background and #44474B thin stroke
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Keyboard Setup Wizard",
                        color = Color(0xFFE2E2E6),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Step 1: Enable Service
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "Step 1: Enable Keyboard", color = Color(0xFFE2E2E6), fontSize = 14.sp)
                            Text(
                                text = if (isKeyboardEnabled) "Configured in settings" else "Toggle system service settings",
                                color = if (isKeyboardEnabled) Color(0xFF81C784) else Color(0xFFE57373),
                                fontSize = 11.sp
                            )
                        }
                        if (!isKeyboardEnabled) {
                            Button(
                                onClick = onEnableKeyboard,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary, // #D1E4FF
                                    contentColor = MaterialTheme.colorScheme.onPrimary  // #00314F
                                ),
                                modifier = Modifier.testTag("enable_keyboard_btn")
                            ) {
                                Text("Enable", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Text(
                                text = "Active",
                                color = Color(0xFF81C784),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(end = 12.dp)
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.secondary)

                    // Step 2: Set Active
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "Step 2: Choose active Keyboard", color = Color(0xFFE2E2E6), fontSize = 14.sp)
                            Text(
                                text = if (isKeyboardSelected) "Currently default active IME" else "Switch input method to 'Tanglish Keyboard'",
                                color = if (isKeyboardSelected) Color(0xFF81C784) else Color(0xFF909094),
                                fontSize = 11.sp
                            )
                        }
                        if (!isKeyboardSelected) {
                            Button(
                                onClick = onSelectKeyboard,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                modifier = Modifier.testTag("select_keyboard_btn")
                            ) {
                                Text("Switch", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Text(
                                text = "Selected",
                                color = Color(0xFF81C784),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(end = 12.dp)
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.secondary)

                    // Record permission check
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "Step 3: Microphone Permission", color = Color(0xFFE2E2E6), fontSize = 14.sp)
                            Text(
                                text = if (hasMicPermission) "Granted! Voice input is ready." else "Required for spoken Tamil voice-to-text",
                                color = if (hasMicPermission) Color(0xFF81C784) else Color(0xFFFFB74D),
                                fontSize = 11.sp
                            )
                        }
                        if (!hasMicPermission) {
                            Button(
                                onClick = { permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFFB74D),
                                    contentColor = Color(0xFF1A1C1E)
                                ),
                                modifier = Modifier.testTag("grant_permission_btn")
                            ) {
                                Text("Grant", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Text(text = "OK", color = Color(0xFF81C784), fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(end = 12.dp))
                        }
                    }
                }
            }
        }

        // Layout Options Selector Card with slate background & border
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Layout Selection",
                        color = Color(0xFFE2E2E6),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Toggle your keyboard key style alignment to fit typing preferences:",
                        color = Color(0xFF909094),
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (layoutQwertz) "QWERTZ Layout Active (German style)" else "QWERTY Layout Active (Standard style)",
                            color = Color(0xFFE2E2E6),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Switch(
                            checked = layoutQwertz,
                            onCheckedChange = { isGerman ->
                                layoutQwertz = isGerman
                                prefs.edit().putBoolean("layout_qwertz", isGerman).apply()
                                Toast.makeText(context, "Layout style saved! Re-open keyboard to apply.", Toast.LENGTH_SHORT).show()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                uncheckedThumbColor = Color(0xFF909094),
                                uncheckedTrackColor = MaterialTheme.colorScheme.secondary
                            ),
                            modifier = Modifier.testTag("layout_switch")
                        )
                    }
                }
            }
        }

        // Battery Optimization Profile Selection Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Battery Optimization Profiles",
                        color = Color(0xFFE2E2E6),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Select dynamic engine profiles to prioritize performance or extend usage battery life:",
                        color = Color(0xFF909094),
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // Profiles Row switcher with clean minimal borders
                    val profiles = listOf("Performance", "Balanced", "Battery Saver")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        profiles.forEach { profile ->
                            val isSelected = selectedProfile == profile
                            val background = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                            val textColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else Color(0xFF909094)

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(background)
                                    .clickable {
                                        selectedProfile = profile
                                        prefs.edit().putString("battery_profile", profile).apply()
                                        Toast.makeText(context, "Set engine profile to '$profile'", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(vertical = 10.dp)
                                    .testTag("opt_profile_$profile"),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = profile,
                                        color = textColor,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center
                                    )
                                    val desc = when(profile) {
                                        "Performance" -> "Max Haptics"
                                        "Balanced" -> "Mid Haptics"
                                        else -> "Zero Haptics"
                                    }
                                    Text(
                                        text = desc,
                                        color = if (isSelected) textColor.copy(0.7f) else Color(0xFF909094).copy(0.7f),
                                        fontSize = 8.sp,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Playground Test Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Keyboard Playground Test Field",
                        color = Color(0xFFE2E2E6),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Once 'Tanglish Keyboard' is enabled, tap this box to trigger and test your typing & transliteration offline!",
                        color = Color(0xFF909094),
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    TextField(
                        value = playgroundText,
                        onValueChange = { playgroundText = it },
                        placeholder = { Text("Click here to test your keyboard...", color = Color(0xFF909094)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("playground_text_field"),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.background,
                            unfocusedContainerColor = MaterialTheme.colorScheme.background,
                            focusedTextColor = Color(0xFFE2E2E6),
                            unfocusedTextColor = Color(0xFFE2E2E6),
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent
                        ),
                        singleLine = false
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Helpful hint banner for the Speech Voice Catcher feature
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF2D3033))
                            .padding(10.dp)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Voice Catcher Guideline", color = Color(0xFFD1E4FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "To use spoken Tamil voice-to-text, make sure 'Tanglish Keyboard' is chosen. Tap the text field above, click the 'Speak Tamil (English preserved)' option, and speak. It types in beautiful, natural phonetics!",
                                color = Color(0xFF909094),
                                fontSize = 11.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Quick Transliteration rules checker
                    if (TamilToTanglishTransliterator.isTamilWord(playgroundText)) {
                        val simpleEng = TamilToTanglishTransliterator.transliterateSentence(
                            playgroundText, 
                            customWords.associate { it.tamilWord to it.customTanglish }
                        )
                        Text(
                            text = "Instant core engine translit preview:\n$simpleEng",
                            color = Color(0xFFD1E4FF),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 4.dp).testTag("live_translation_output")
                        )
                    }
                }
            }
        }

        // Custom Dictionary Manager Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Custom User Dictionary",
                            color = Color(0xFFE2E2E6),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Button(
                            onClick = { isAddingWord = !isAddingWord },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier.testTag("add_custom_word_panel_btn")
                        ) {
                            Text(if (isAddingWord) "Cancel" else "Add New", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Adding Word Flow Input Panel
                    AnimatedVisibility(visible = isAddingWord) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp)
                                .background(MaterialTheme.colorScheme.background)
                                .padding(12.dp)
                                .clip(RoundedCornerShape(8.dp))
                        ) {
                            Text("New Translation Association Rule", color = Color(0xFFE2E2E6), fontSize = 13.sp, fontWeight = FontWeight.Bold)

                            OutlinedTextField(
                                value = tamilInput,
                                onValueChange = { tamilInput = it },
                                label = { Text("Tamil Word e.g., வணக்கம்") },
                                textStyle = LocalTextStyle.current.copy(color = Color(0xFFE2E2E6)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("tamil_input_field")
                            )

                            OutlinedTextField(
                                value = tanglishInput,
                                onValueChange = { tanglishInput = it },
                                label = { Text("Spelling e.g., vanakkam") },
                                textStyle = LocalTextStyle.current.copy(color = Color(0xFFE2E2E6)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("tanglish_input_field")
                            )

                            Button(
                                onClick = {
                                    if (tamilInput.isNotBlank() && tanglishInput.isNotBlank()) {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                repository.insert(
                                                    UserWord(
                                                        tamilWord = tamilInput.trim(),
                                                        customTanglish = tanglishInput.trim()
                                                    )
                                                )
                                            }
                                            tamilInput = ""
                                            tanglishInput = ""
                                            isAddingWord = false
                                            Toast.makeText(context, "Added custom rule!", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        Toast.makeText(context, "Please enter both fields", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF81C784),
                                    contentColor = Color(0xFF1A1C1E)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("save_custom_word_btn")
                            ) {
                                Text("Save Action", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Customize how the transliterator handles exceptions inside the pipeline. Rules take precedence over auto-translation engines.",
                        color = Color(0xFF909094),
                        fontSize = 11.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (customWords.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Dictionary Empty. Add items or use auto-transliteration rules.",
                                color = Color(0xFF909094),
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        // Display items
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            customWords.take(15).forEach { word ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.background)
                                        .padding(12.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(
                                            text = word.tamilWord,
                                            color = Color(0xFFE2E2E6),
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Transliterates to: ${word.customTanglish}",
                                            color = Color(0xFFD1E4FF),
                                            fontSize = 12.sp
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                withContext(Dispatchers.IO) {
                                                    repository.deleteWordByKey(word.tamilWord)
                                                }
                                                Toast.makeText(context, "Rule removed", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.testTag("delete_${word.tamilWord}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Delete,
                                            contentDescription = "Delete custom translator association",
                                            tint = Color(0xFFE57373)
                                        )
                                    }
                                }
                            }
                            if (customWords.size > 15) {
                                Text(
                                    text = "+ ${customWords.size - 15} more rules in dictionary.",
                                    color = Color(0xFF909094),
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Signature
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Designed by charsware",
                    color = Color(0xFF909094),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Version 1.1.7",
                    color = Color(0xFF909094).copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
