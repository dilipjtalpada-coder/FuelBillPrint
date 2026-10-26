package com.fuelbill.print

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.*

// ==========================================
// 1. DATA LAYER (ROOM DATABASE)
// ==========================================
@Entity(tableName = "receipts")
data class ReceiptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receiptNo: String,
    val invoiceRef: String,
    val timestamp: Long,
    val pumpName: String,
    val pumpAddress: String,
    val fuelType: String,
    val rate: Double,
    val volume: Double,
    val amount: Double,
    val vehicleNo: String,
    val customerPhone: String,
    val paymentMode: String,
    val isDuplicate: Boolean = false
)

@Dao
interface ReceiptDao {
    @Insert
    suspend fun insert(receipt: ReceiptEntity): Long

    @Query("SELECT * FROM receipts ORDER BY timestamp DESC")
    suspend fun getAll(): List<ReceiptEntity>

    @Query("SELECT COUNT(*) FROM receipts WHERE timestamp >= :startOfDay")
    suspend fun getTodayCount(startOfDay: Long): Int

    @Query("SELECT SUM(amount) FROM receipts WHERE timestamp >= :startOfDay")
    suspend fun getTodaySales(startOfDay: Long): Double?

    @Query("UPDATE receipts SET isDuplicate = 1 WHERE id = :id")
    suspend fun markDuplicate(id: Long)
}

@Database(entities = [ReceiptEntity::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): ReceiptDao

    companion object {
        @Volatile private var instance: AppDb? = null
        fun get(ctx: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "fuel.db")
                .fallbackToDestructiveMigration().build().also { instance = it }
        }
    }
}

// ==========================================
// 2. DOMAIN LAYER (CALCULATIONS)
// ==========================================
object CalcEngine {
    fun calcAmount(rateStr: String, volStr: String): BigDecimal {
        val rate = rateStr.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val vol = volStr.toBigDecimalOrNull() ?: BigDecimal.ZERO
        return rate.multiply(vol).setScale(2, RoundingMode.HALF_UP)
    }

    fun calcVolume(amtStr: String, rateStr: String): BigDecimal {
        val amt = amtStr.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val rate = rateStr.toBigDecimalOrNull() ?: BigDecimal.ZERO
        if (rate.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO
        return amt.divide(rate, 2, RoundingMode.HALF_UP)
    }

    fun isReconciled(rateStr: String, volStr: String, amtStr: String): Boolean {
        val expected = calcAmount(rateStr, volStr)
        val entered = amtStr.toBigDecimalOrNull() ?: BigDecimal.ZERO
        return expected.subtract(entered).abs().compareTo(BigDecimal("0.05")) <= 0
    }
}

// ==========================================
// 3. PRINTER ENGINE (ESC/POS & BLUETOOTH)
// ==========================================
class EscPosDriver(private val is80mm: Boolean) {
    private val cols = if (is80mm) 48 else 32

    private fun line(): String = "-".repeat(cols) + "\n"
    private fun row(left: String, right: String): String {
        val spaces = cols - (left.length + right.length)
        return if (spaces >= 0) left + " ".repeat(spaces) + right + "\n"
        else left.take((cols - right.length - 1).coerceAtLeast(0)) + " " + right + "\n"
    }

    fun buildReceipt(r: ReceiptEntity, isReprint: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        val dt = Date(r.timestamp)
        val dFmt = SimpleDateFormat("yy/MM/dd", Locale.ENGLISH)
        val tFmt = SimpleDateFormat("HH:mm", Locale.ENGLISH)
        val nowFmt = SimpleDateFormat("yy/MM/dd HH:mm", Locale.ENGLISH)

        out.write(byteArrayOf(0x1B, 0x40)) // Init
        out.write(byteArrayOf(0x1B, 0x61, 0x01)) // Center
        out.write(byteArrayOf(0x1B, 0x45, 0x01)) // Bold ON
        out.write("${r.pumpName.trim().uppercase()}\n".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x1B, 0x45, 0x00)) // Bold OFF

        if (r.pumpAddress.isNotBlank()) out.write("${r.pumpAddress.trim()}\n".toByteArray(Charsets.US_ASCII))
        out.write(line().toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x1B, 0x45, 0x01))
        out.write(if (isReprint || r.isDuplicate) "*** DUPLICATE CUSTOMER COPY ***\n".toByteArray(Charsets.US_ASCII)
                  else "CUSTOMER COPY\n".toByteArray(Charsets.US_ASCII))
        out.write("NON-OFFICIAL RECEIPT\n".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x1B, 0x45, 0x00))
        out.write(line().toByteArray(Charsets.US_ASCII))

        out.write(byteArrayOf(0x1B, 0x61, 0x00)) // Left
        out.write(row("Receipt No :", r.receiptNo).toByteArray(Charsets.US_ASCII))
        if (r.invoiceRef.isNotBlank()) out.write(row("Inv.No/Ref :", r.invoiceRef).toByteArray(Charsets.US_ASCII))
        out.write(row("Product    :", r.fuelType).toByteArray(Charsets.US_ASCII))
        out.write(row("Rate(Rs/L) :", String.format(Locale.ENGLISH, "%06.2f", r.rate)).toByteArray(Charsets.US_ASCII))
        out.write(row("Volume(L)  :", String.format(Locale.ENGLISH, "%08.2f", r.volume)).toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x1B, 0x45, 0x01))
        out.write(row("Amount(Rs) :", String.format(Locale.ENGLISH, "%08.2f", r.amount)).toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x1B, 0x45, 0x00))

        out.write(row("Vehicle No :", if (r.vehicleNo.isNotBlank()) r.vehicleNo else "Not Entered").toByteArray(Charsets.US_ASCII))
        out.write(row("Mobile No  :", if (r.customerPhone.isNotBlank()) r.customerPhone else "Not Entered").toByteArray(Charsets.US_ASCII))
        out.write(row("Payment    :", r.paymentMode).toByteArray(Charsets.US_ASCII))
        out.write(row("Date       :", dFmt.format(dt)).toByteArray(Charsets.US_ASCII))
        out.write(row("Time       :", tFmt.format(dt)).toByteArray(Charsets.US_ASCII))

        out.write(line().toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x1B, 0x61, 0x01))
        out.write("Thank You! Please Visit Again.\n".toByteArray(Charsets.US_ASCII))
        out.write(line().toByteArray(Charsets.US_ASCII))
        out.write("This is a customer transaction record,\nnot an official oil company invoice.\n".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x1B, 0x61, 0x00))
        out.write(row("Printed on :", nowFmt.format(Date())).toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x1B, 0x64, 0x03)) // Feed 3
        out.write(byteArrayOf(0x1D, 0x56, 0x41, 0x00)) // Cut
        return out.toByteArray()
    }

    fun buildTest(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x1B, 0x40, 0x1B, 0x61, 0x01, 0x1B, 0x45, 0x01))
        out.write("PRINTER TEST OK\n".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x1B, 0x45, 0x00))
        out.write(line().toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x1B, 0x61, 0x00))
        out.write(row("Width:", if (is80mm) "80mm" else "58mm").toByteArray(Charsets.US_ASCII))
        out.write(row("Status:", "Connected & Ready").toByteArray(Charsets.US_ASCII))
        out.write(line().toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x1B, 0x64, 0x03, 0x1D, 0x56, 0x41, 0x00))
        return out.toByteArray()
    }
}

class BluetoothManagerHelper(private val ctx: Context) {
    private val adapter: BluetoothAdapter? = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private var socket: BluetoothSocket? = null
    private var outStream: OutputStream? = null
    private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    @SuppressLint("MissingPermission")
    fun getPaired(): List<BluetoothDevice> = try { adapter?.bondedDevices?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }

    fun isConnected(): Boolean = socket?.isConnected == true

    @SuppressLint("MissingPermission")
    suspend fun connect(mac: String): Result<Unit> = withContext(Dispatchers.IO) {
        disconnect()
        try {
            val dev = adapter?.getRemoteDevice(mac) ?: return@withContext Result.failure(Exception("Device not found"))
            adapter.cancelDiscovery()
            val s = dev.createRfcommSocketToServiceRecord(SPP_UUID)
            s.connect()
            socket = s
            outStream = s.outputStream
            Result.success(Unit)
        } catch (e: Exception) {
            disconnect()
            Result.failure(e)
        }
    }

    suspend fun send(data: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        val o = outStream ?: return@withContext Result.failure(Exception("Printer not connected"))
        try {
            o.write(data)
            o.flush()
            Result.success(Unit)
        } catch (e: Exception) {
            disconnect()
            Result.failure(e)
        }
    }

    fun disconnect() {
        try { outStream?.close(); socket?.close() } catch (_: Exception) {}
        outStream = null; socket = null
    }
}

// ==========================================
// 4. MAIN ACTIVITY & COMPOSE USER INTERFACE
// ==========================================
class MainActivity : ComponentActivity() {
    private lateinit var db: AppDb
    private lateinit var bt: BluetoothManagerHelper
    private lateinit var sp: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = AppDb.get(this)
        bt = BluetoothManagerHelper(this)
        sp = getSharedPreferences("fuel_prefs", Context.MODE_PRIVATE)

        setContent {
            MaterialTheme {
                AppNavigator(db, bt, sp)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bt.disconnect()
    }
}

@Composable
fun AppNavigator(db: AppDb, bt: BluetoothManagerHelper, sp: SharedPreferences) {
    var screen by remember { mutableStateOf("home") }
    var previewReceipt by remember { mutableStateOf<ReceiptEntity?>(null) }
    val scope = rememberCoroutineScope()
    var is80mm by remember { mutableStateOf(sp.getBoolean("is_80mm", false)) }

    when (screen) {
        "home" -> HomeScreen(
            db = db,
            bt = bt,
            sp = sp,
            onNav = { screen = it },
            onTestPrint = {
                scope.launch {
                    val driver = EscPosDriver(is80mm)
                    bt.send(driver.buildTest())
                }
            }
        )
        "new" -> NewBillScreen(
            sp = sp,
            onBack = { screen = "home" },
            onGenerate = {
                previewReceipt = it
                screen = "preview"
            }
        )
        "preview" -> previewReceipt?.let { r ->
            PreviewScreen(
                receipt = r,
                is80mm = is80mm,
                bt = bt,
                db = db,
                onBack = { screen = "new" },
                onDone = { screen = "home" }
            )
        }
        "connect" -> ConnectScreen(bt = bt, sp = sp, onBack = { screen = "home" })
        "history" -> HistoryScreen(db = db, bt = bt, is80mm = is80mm, onBack = { screen = "home" })
        "settings" -> SettingsScreen(
            sp = sp,
            onBack = {
                is80mm = sp.getBoolean("is_80mm", false)
                screen = "home"
            }
        )
    }
}

@Composable
fun HomeScreen(db: AppDb, bt: BluetoothManagerHelper, sp: SharedPreferences, onNav: (String) -> Unit, onTestPrint: () -> Unit) {
    val pumpName = remember { sp.getString("pump_name", "CHETAK MOTARS") ?: "CHETAK MOTARS" }
    var todayBills by remember { mutableIntStateOf(0) }
    var todayTotal by remember { mutableDoubleStateOf(0.0) }

    LaunchedEffect(Unit) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        todayBills = db.dao().getTodayCount(cal.timeInMillis)
        todayTotal = db.dao().getTodaySales(cal.timeInMillis) ?: 0.0
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFF5F7FA)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF0D47A1))) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(pumpName, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (bt.isConnected()) Icons.Default.Print else Icons.Default.PrintDisabled,
                        contentDescription = null,
                        tint = if (bt.isConnected()) Color(0xFF00E676) else Color(0xFFFF8A80),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (bt.isConnected()) "Printer Connected" else "Printer Disconnected", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Card(modifier = Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Today's Bills", fontSize = 12.sp, color = Color.Gray)
                    Text("$todayBills", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
            Card(modifier = Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Today's Total", fontSize = 12.sp, color = Color.Gray)
                    Text("₹${String.format(Locale.ENGLISH, "%.2f", todayTotal)}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                }
            }
        }

        Button(onClick = { onNav("new") }, modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))) {
            Icon(Icons.Default.Add, contentDescription = null); Spacer(modifier = Modifier.width(8.dp))
            Text("नया बिल बनाएं (New Bill)", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }

        Button(onClick = { onNav("connect") }, modifier = Modifier.fillMaxWidth().height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F))) {
            Icon(Icons.Default.Bluetooth, contentDescription = null); Spacer(modifier = Modifier.width(8.dp))
            Text("Bluetooth Printer Connect")
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { onNav("history") }, modifier = Modifier.weight(1f)) {
                Text("पुराने बिल")
            }
            OutlinedButton(onClick = onTestPrint, modifier = Modifier.weight(1f)) {
                Text("Printer Test")
            }
        }

        OutlinedButton(onClick = { onNav("settings") }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Settings, contentDescription = null); Spacer(modifier = Modifier.width(8.dp))
            Text("Settings (रेट व नाम बदलें)")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewBillScreen(sp: SharedPreferences, onBack: () -> Unit, onGenerate: (ReceiptEntity) -> Unit) {
    val pumpName = remember { sp.getString("pump_name", "CHETAK MOTARS") ?: "CHETAK MOTARS" }
    val pumpAddr = remember { sp.getString("pump_addr", "National Highway Road") ?: "" }
    var fuelType by remember { mutableStateOf("Diesel") }
    var rate by remember { mutableStateOf(sp.getString("diesel_rate", "92.30") ?: "92.30") }
    var vol by remember { mutableStateOf("") }
    var amt by remember { mutableStateOf("") }
    var veh by remember { mutableStateOf("") }
    var ref by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var payMode by remember { mutableStateOf("Cash") }
    var err by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(fuelType) {
        rate = if (fuelType == "Diesel") sp.getString("diesel_rate", "92.30") ?: "92.30"
               else sp.getString("petrol_rate", "96.50") ?: "96.50"
    }

    Scaffold(topBar = { TopAppBar(title = { Text("नया ईंधन बिल") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } }) }) { pad ->
        Column(modifier = Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                listOf("Diesel", "Petrol").forEach { t ->
                    Button(
                        onClick = { fuelType = t },
                        modifier = Modifier.weight(1f).padding(4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (fuelType == t) Color(0xFF0D47A1) else Color(0xFFE0E0E0), contentColor = if (fuelType == t) Color.White else Color.Black)
                    ) { Text(t, fontWeight = FontWeight.Bold) }
                }
            }

            OutlinedTextField(value = rate, onValueChange = { rate = it; err = null }, label = { Text("Rate (₹/L)") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(value = vol, onValueChange = { vol = it; err = null }, label = { Text("Volume (Litres)") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(value = amt, onValueChange = { amt = it; err = null }, label = { Text("Total Amount (₹)") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val a = CalcEngine.calcAmount(rate, vol)
                    if (a.signum() > 0) amt = a.toPlainString() else err = "दर और मात्रा सही डालें"
                }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF455A64))) {
                    Text("Calc Amount")
                }
                Button(onClick = {
                    val v = CalcEngine.calcVolume(amt, rate)
                    if (v.signum() > 0) vol = v.toPlainString() else err = "रुपये और दर सही डालें"
                }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF455A64))) {
                    Text("Calc Volume")
                }
            }

            err?.let { Text(it, color = Color.Red, fontSize = 12.sp) }
            Divider()

            OutlinedTextField(value = veh, onValueChange = { veh = it.uppercase() }, label = { Text("गाड़ी नंबर (Optional)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = ref, onValueChange = { ref = it }, label = { Text("Ref / Memo No (Optional)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Customer Mobile (Optional)") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Cash", "UPI", "Card").forEach { m ->
                    FilterChip(selected = payMode == m, onClick = { payMode = m }, label = { Text(m) })
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = {
                    val rVal = rate.toDoubleOrNull() ?: 0.0
                    val vVal = vol.toDoubleOrNull() ?: 0.0
                    val aVal = amt.toDoubleOrNull() ?: 0.0
                    if (rVal <= 0.0 || vVal <= 0.0 || aVal <= 0.0) {
                        err = "Rate, Volume और Amount तीनों सही होने चाहिए!"
                        return@Button
                    }
                    val receipt = ReceiptEntity(
                        receiptNo = String.format(Locale.ENGLISH, "%06d", (100000..999999).random()),
                        invoiceRef = ref,
                        timestamp = System.currentTimeMillis(),
                        pumpName = pumpName,
                        pumpAddress = pumpAddr,
                        fuelType = fuelType,
                        rate = rVal,
                        volume = vVal,
                        amount = aVal,
                        vehicleNo = veh,
                        customerPhone = phone,
                        paymentMode = payMode
                    )
                    onGenerate(receipt)
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
            ) {
                Text("बिल प्रीव्यू देखें (Review Bill)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(receipt: ReceiptEntity, is80mm: Boolean, bt: BluetoothManagerHelper, db: AppDb, onBack: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("रसीद प्रीव्यू") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } }) }) { pad ->
        Column(modifier = Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Card(
                modifier = Modifier.width(if (is80mm) 320.dp else 280.dp).border(1.dp, Color.Gray, RoundedCornerShape(4.dp)),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(receipt.pumpName.uppercase(), fontWeight = FontWeight.Bold, fontSize = 15.sp, textAlign = TextAlign.Center)
                    if (receipt.pumpAddress.isNotBlank()) Text(receipt.pumpAddress, fontSize = 10.sp, color = Color.Gray, textAlign = TextAlign.Center)
                    Text("--------------------------------", fontFamily = FontFamily.Monospace)
                    Text("CUSTOMER COPY", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("NON-OFFICIAL RECEIPT", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    Text("--------------------------------", fontFamily = FontFamily.Monospace)

                    Text("Receipt No : ${receipt.receiptNo}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.fillMaxWidth())
                    if (receipt.invoiceRef.isNotBlank()) Text("Inv.No/Ref : ${receipt.invoiceRef}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.fillMaxWidth())
                    Text("Product    : ${receipt.fuelType}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.fillMaxWidth())
                    Text("Rate(Rs/L) : ${String.format(Locale.ENGLISH, "%06.2f", receipt.rate)}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.fillMaxWidth())
                    Text("Volume(L)  : ${String.format(Locale.ENGLISH, "%08.2f", receipt.volume)}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.fillMaxWidth())
                    Text("Amount(Rs) : ${String.format(Locale.ENGLISH, "%08.2f", receipt.amount)}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
                    Text("Vehicle No : ${if (receipt.vehicleNo.isNotBlank()) receipt.vehicleNo else "Not Entered"}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.fillMaxWidth())
                    Text("Payment    : ${receipt.paymentMode}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.fillMaxWidth())

                    Text("--------------------------------", fontFamily = FontFamily.Monospace)
                    Text("Thank You! Please Visit Again.", fontSize = 10.sp, textAlign = TextAlign.Center)
                    Text("This is a customer transaction record,\nnot an official oil company invoice.", fontSize = 8.sp, color = Color.Gray, textAlign = TextAlign.Center)
                }
            }

            status?.let { Spacer(modifier = Modifier.height(10.dp)); Text(it, color = Color.Red, fontSize = 12.sp) }
            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            db.dao().insert(receipt)
                            busy = false
                            onDone()
                        }
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF455A64)),
                    enabled = !busy
                ) { Text("Save Only") }

                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            db.dao().insert(receipt)
                            val driver = EscPosDriver(is80mm)
                            val res = bt.send(driver.buildReceipt(receipt))
                            busy = false
                            if (res.isSuccess) onDone() else status = "प्रिंट नहीं हुआ: प्रिंटर कनेक्ट है या नहीं जांचें!"
                        }
                    },
                    modifier = Modifier.weight(1.3f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)),
                    enabled = !busy
                ) {
                    Icon(Icons.Default.Print, null); Spacer(modifier = Modifier.width(6.dp))
                    Text("Save & Print")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(bt: BluetoothManagerHelper, sp: SharedPreferences, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    var msg by remember { mutableStateOf("Searching paired devices...") }
    var busy by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) perms[Manifest.permission.BLUETOOTH_CONNECT] == true else true
        if (granted) {
            devices = bt.getPaired()
            msg = if (devices.isEmpty()) "कोई पेयर किया हुआ प्रिंटर नहीं मिला। फ़ोन सेटिंग से पेयर करें।" else "अपना प्रिंटर चुनें:"
        } else {
            msg = "ब्लूटूथ परमिशन नहीं दी गई!"
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
        } else {
            devices = bt.getPaired()
            msg = if (devices.isEmpty()) "कोई पेयर किया हुआ प्रिंटर नहीं मिला।" else "अपना प्रिंटर चुनें:"
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("थर्मल प्रिंटर कनेक्ट करें") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } }) }) { pad ->
        Column(modifier = Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
            Text(msg, fontSize = 14.sp, color = Color.DarkGray)
            if (busy) { Spacer(modifier = Modifier.height(8.dp)); LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            Spacer(modifier = Modifier.height(10.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(devices) { d ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) {
                            scope.launch {
                                busy = true
                                msg = "Connecting to ${d.name ?: d.address}..."
                                val r = bt.connect(d.address)
                                busy = false
                                if (r.isSuccess) {
                                    sp.edit().putString("last_printer", d.address).apply()
                                    onBack()
                                } else {
                                    msg = "कनेक्ट नहीं हो सका! प्रिंटर ऑन है या नहीं देखें।"
                                }
                            }
                        },
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Bluetooth, null, tint = Color(0xFF1976D2))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(d.name ?: "Unknown Device", fontWeight = FontWeight.Bold)
                                Text(d.address, fontSize = 11.sp, color = Color.Gray)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(db: AppDb, bt: BluetoothManagerHelper, is80mm: Boolean, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var receipts by remember { mutableStateOf<List<ReceiptEntity>>(emptyList()) }
    var banner by remember { mutableStateOf<String?>(null) }
    val dFmt = remember { SimpleDateFormat("dd/MM/yy HH:mm", Locale.ENGLISH) }

    LaunchedEffect(Unit) { receipts = db.dao().getAll() }

    Scaffold(topBar = { TopAppBar(title = { Text("पुराने बिल (इतिहास)") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } }) }) { pad ->
        Column(modifier = Modifier.padding(pad).fillMaxSize().padding(12.dp)) {
            banner?.let { Text(it, color = Color.Blue, fontSize = 12.sp); Spacer(modifier = Modifier.height(6.dp)) }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(receipts) { r ->
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(r.receiptNo, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text("₹${String.format(Locale.ENGLISH, "%.2f", r.amount)}", fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                            }
                            Text("${r.fuelType} | ${r.volume} L @ ₹${r.rate} | ${dFmt.format(Date(r.timestamp))}", fontSize = 12.sp, color = Color.DarkGray)
                            if (r.vehicleNo.isNotBlank()) Text("Vehicle: ${r.vehicleNo}", fontSize = 11.sp, color = Color.Gray)

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = {
                                    scope.launch {
                                        db.dao().markDuplicate(r.id)
                                        val driver = EscPosDriver(is80mm)
                                        val res = bt.send(driver.buildReceipt(r, isReprint = true))
                                        banner = if (res.isSuccess) "डुप्लीकेट रसीद प्रिंट हो गई!" else "प्रिंट नहीं हुआ: प्रिंटर कनेक्ट करें।"
                                    }
                                }) {
                                    Icon(Icons.Default.Print, null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Reprint")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(sp: SharedPreferences, onBack: () -> Unit) {
    var pName by remember { mutableStateOf(sp.getString("pump_name", "CHETAK MOTARS") ?: "CHETAK MOTARS") }
    var pAddr by remember { mutableStateOf(sp.getString("pump_addr", "National Highway Road") ?: "") }
    var petRate by remember { mutableStateOf(sp.getString("petrol_rate", "96.50") ?: "96.50") }
    var dieRate by remember { mutableStateOf(sp.getString("diesel_rate", "92.30") ?: "92.30") }
    var is80 by remember { mutableStateOf(sp.getBoolean("is_80mm", false)) }

    Scaffold(topBar = { TopAppBar(title = { Text("सेटिंग्स") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } }) }) { pad ->
        Column(modifier = Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = pName, onValueChange = { pName = it }, label = { Text("पंप का नाम (Pump Name)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = pAddr, onValueChange = { pAddr = it }, label = { Text("पंप का पता (Address)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = petRate, onValueChange = { petRate = it }, label = { Text("पेट्रोल डिफ़ॉल्ट रेट (₹/L)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = dieRate, onValueChange = { dieRate = it }, label = { Text("डीजल डिफ़ॉल्ट रेट (₹/L)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("80mm रोल इस्तेमाल करें (डिफ़ॉल्ट 58mm है)")
                Switch(checked = is80, onCheckedChange = { is80 = it })
            }

            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = {
                    sp.edit()
                        .putString("pump_name", pName)
                        .putString("pump_addr", pAddr)
                        .putString("petrol_rate", petRate)
                        .putString("diesel_rate", dieRate)
                        .putBoolean("is_80mm", is80)
                        .apply()
                    onBack()
                },
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text("सेटिंग्स सुरक्षित करें (Save)") }
        }
    }
}
