package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val action = intent.action
        val type = intent.type

        if (Intent.ACTION_SEND == action && "text/plain" == type) {
            handleSendText(intent)
        }

        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    RoCheapScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    private fun handleSendText(intent: Intent) {
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (sharedText != null && sharedText.contains("http")) {
            Toast.makeText(this, "Resolving URL...", Toast.LENGTH_SHORT).show()
            val urlString = sharedText.split("\\s+".toRegex()).firstOrNull { it.startsWith("http") } ?: sharedText

            lifecycleScope.launch {
                val result = resolveUrl(urlString)
                result.fold(
                    onSuccess = { id ->
                        copyToClipboard(id)
                        Toast.makeText(this@MainActivity, "ID Copied: $id", Toast.LENGTH_SHORT).show()
                        launchRoblox()
                        finish()
                    },
                    onFailure = { error ->
                        Toast.makeText(this@MainActivity, "Error: ${error.message}", Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }

    private suspend fun resolveUrl(initialUrl: String): Result<String> {
        var currentUrl = initialUrl
        var htmlContent: String? = null
        var redirectCount = 0
        val maxRedirects = 10
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

        return withContext(Dispatchers.IO) {
            try {
                // Ensure initial string has protocol
                if (!currentUrl.startsWith("http")) {
                    currentUrl = "https://$currentUrl"
                }

                while (redirectCount < maxRedirects) {
                    val url = URL(currentUrl)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("User-Agent", userAgent)
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000

                    val responseCode = connection.responseCode
                    if (responseCode in 300..399) {
                        val location = connection.getHeaderField("Location")
                        if (location != null) {
                            currentUrl = location
                            if (!currentUrl.startsWith("http")) {
                                currentUrl = URL(url, currentUrl).toString()
                            }
                            redirectCount++
                            connection.disconnect()
                            continue
                        }
                    }

                    if (responseCode == 200) {
                        htmlContent = connection.inputStream.bufferedReader().use { it.readText() }
                        connection.disconnect()
                        break
                    }
                    
                    connection.disconnect()
                    break
                }

                // 1. Check URL for ID
                val pathRegex = Regex("""/catalog/(\d{7,12})""")
                val pathMatch = pathRegex.find(currentUrl)
                if (pathMatch != null) {
                    return@withContext Result.success(pathMatch.groupValues[1])
                }
                
                val generalUrlRegex = Regex("""/(\d{7,12})(?:/|$)""")
                val generalMatch = generalUrlRegex.find(currentUrl)
                if (generalMatch != null) {
                    return@withContext Result.success(generalMatch.groupValues[1])
                }

                // 2. Check HTML for ID
                if (htmlContent != null) {
                    val targetIdRegex = Regex("""targetId["']?\s*[:=]\s*["']?(\d{7,12})["']?""", RegexOption.IGNORE_CASE)
                    val assetIdRegex = Regex("""AssetId["']?\s*[:=]\s*["']?(\d{7,12})["']?""", RegexOption.IGNORE_CASE)
                    val navRegex = Regex("""navigation/item\?id=(\d{7,12})""", RegexOption.IGNORE_CASE)
                    val itemTargetIdRegex = Regex("""data-item-id=["']?(\d{7,12})["']?""")
                    val expectedItemRegex = Regex("""data-expected-price.*?data-item-target-id=["']?(\d{7,12})["']?""")
                    val dataTargetIdRegex = Regex("""data-target-id=["']?(\d{7,12})["']?""")
                    
                    val match = targetIdRegex.find(htmlContent!!) 
                        ?: assetIdRegex.find(htmlContent!!) 
                        ?: navRegex.find(htmlContent!!)
                        ?: itemTargetIdRegex.find(htmlContent!!)
                        ?: expectedItemRegex.find(htmlContent!!)
                        ?: dataTargetIdRegex.find(htmlContent!!)

                    if (match != null) {
                        return@withContext Result.success(match.groupValues[1])
                    }

                    // Provide snippet on error
                    val snippetLength = min(htmlContent!!.length, 120)
                    val snippet = htmlContent!!.substring(0, snippetLength)
                    return@withContext Result.failure(Exception("ID not found. HTML snippet: $snippet"))
                }

                Result.failure(Exception("Could not fetch page or find ID."))

            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Roblox Item ID", text)
        clipboard.setPrimaryClip(clip)
    }

    private fun launchRoblox() {
        val sharedPreferences = getSharedPreferences("rocheap_prefs", Context.MODE_PRIVATE)
        val placeId = sharedPreferences.getString("place_id", "123456789") ?: "123456789"
        
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("roblox://placeId=$placeId"))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Roblox app not installed.", Toast.LENGTH_SHORT).show()
        }
    }
}

// Theme Colors
val BgLight = Color(0xFFFEF7FF)
val Primary = Color(0xFF6750A4)
val PrimaryContainer = Color(0xFFEADDFF)
val OnPrimaryContainer = Color(0xFF21005D)
val SurfaceVariant = Color(0xFFF3EDF7)
val OnSurfaceVariant = Color(0xFF49454F)
val OutlineVariant = Color(0xFFE6E0E9)
val TextPrimary = Color(0xFF1D1B20)
val SecondaryContainer = Color(0xFFECE6F0)
val StatusBg = Color(0xFFB2F0AD)
val StatusText = Color(0xFF143D11)
val StatusBorder = Color(0xFF409139)
val DashedBorder = Color(0xFFD0BCFF)
val NavBg = Color(0xFFF7F2FA)

@Composable
fun RoCheapScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sharedPreferences = context.getSharedPreferences("rocheap_prefs", Context.MODE_PRIVATE)
    var placeId by remember { mutableStateOf(sharedPreferences.getString("place_id", "18565074251") ?: "18565074251") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgLight)
    ) {
        // App Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(PrimaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        tint = OnPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    text = "RoCheap Resolver",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                    letterSpacing = (-0.5).sp
                )
            }
            IconButton(onClick = {}) {
                Icon(Icons.Default.MoreVert, contentDescription = null, tint = TextPrimary)
            }
        }

        // Main Content Area
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Configuration Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceVariant, RoundedCornerShape(24.dp))
                    .border(1.dp, OutlineVariant, RoundedCornerShape(24.dp))
                    .padding(24.dp)
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(bottom = 16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(Primary, RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                        }
                        Column {
                            Text(
                                text = "DESTINATION PLACE",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Primary,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "Target game ID for redirection",
                                fontSize = 12.sp,
                                color = OnSurfaceVariant
                            )
                        }
                    }

                    OutlinedTextField(
                        value = placeId,
                        onValueChange = { placeId = it },
                        label = {
                            Text("Place ID", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        },
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 18.sp,
                            letterSpacing = 2.sp,
                            color = TextPrimary
                        ),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Primary,
                            unfocusedBorderColor = Primary,
                            focusedLabelColor = Primary,
                            unfocusedLabelColor = Primary,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { testTag = "place_id_input" },
                        singleLine = true
                    )

                    Text(
                        text = "\"When you share a Roblox link, this app extracts the item ID and forces a redirect to this specific game.\"",
                        fontSize = 12.sp,
                        color = OnSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            }

            // Central Status Visualization
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(128.dp)
                        .drawBehind {
                            drawCircle(
                                color = DashedBorder,
                                style = Stroke(
                                    width = 4.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 15f))
                                )
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .background(Primary, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowForward,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 8.dp, y = 8.dp)
                            .background(StatusBg, CircleShape)
                            .border(1.dp, StatusBorder, CircleShape)
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "SERVICE ACTIVE",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = StatusText,
                            letterSpacing = 1.sp
                        )
                    }
                }

                Text(
                    text = "Share Target Ready",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                    modifier = Modifier.padding(top = 32.dp, bottom = 8.dp)
                )
                Text(
                    text = "Intercepting ACTION_SEND. Using Desktop User-Agent bypass for multi-layer scraping.",
                    fontSize = 14.sp,
                    color = OnSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }

            // Quick Debug Info Container
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DebugCard(title = "USER AGENT", value = "Chrome 122 Win10", modifier = Modifier.weight(1f))
                DebugCard(title = "EXTRACTION", value = "Recursive 302", modifier = Modifier.weight(1f))
            }
        }

        // Bottom Action Area
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(NavBg)
                .border(1.dp, OutlineVariant)
                .padding(16.dp)
        ) {
            Button(
                onClick = {
                    val editor = sharedPreferences.edit()
                    editor.putString("place_id", placeId)
                    editor.apply()
                    Toast.makeText(context, "Place ID Saved", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .semantics { testTag = "save_button" },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Primary),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "SAVE CONFIGURATION",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Box(
                        modifier = Modifier
                            .width(64.dp)
                            .height(32.dp)
                            .background(Color(0xFFE8DEF8), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("SETUP", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .offset(y = 2.dp)
                        .alpha(0.5f)
                ) {
                    Icon(Icons.Default.Menu, contentDescription = null, tint = OnSurfaceVariant, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("LOGS", fontSize = 10.sp, fontWeight = FontWeight.Medium, color = OnSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun DebugCard(title: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(SecondaryContainer, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text(
            text = title,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = OnSurfaceVariant,
            letterSpacing = 0.5.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
