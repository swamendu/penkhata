package com.example.penkhata

import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

const val APP_VERSION = "3.2.1 \u00b7 iteration 19a"

data class InvItem(val desc: String, val serial: String, val hsn: String, val qty: Double, val rate: Double, val unit: String, val taxRate: Double, val discount: Double = 0.0)
val InvItemListSaver: Saver<List<InvItem>, String> = Saver(
    save = { list -> list.joinToString("\u0002") { "${it.desc}\u0001${it.serial}\u0001${it.hsn}\u0001${it.qty}\u0001${it.rate}\u0001${it.unit}\u0001${it.taxRate}\u0001${it.discount}" } },
    restore = { s ->
        if(s.isEmpty()) emptyList() else s.split("\u0002").mapNotNull { row ->
            val f = row.split("\u0001")
            if(f.size < 7) null else InvItem(f[0], f[1], f[2], f[3].toDoubleOrNull() ?: 0.0, f[4].toDoubleOrNull() ?: 0.0, f[5], f[6].toDoubleOrNull() ?: 18.0, if(f.size > 7) f[7].toDoubleOrNull() ?: 0.0 else 0.0)
        }
    }
)
data class StockEntry(val id: Long, val model: String, val imei: String, val hsn: String,
                      val cost: Double, val mrp: Double, val qty: Int, val supplier: String, val addedOn: String)
// A stock line can hold several IMEIs (one per handset), stored newline-separated.
fun StockEntry.imeiList(): List<String> =
    imei.split("\n", ",", " ").map{ it.trim() }.filter{ it.isNotBlank() }.distinct()
fun StockEntry.imeiLabel(): String {
    val l = imeiList()
    if(l.isEmpty()) return ""
    return "IMEI: " + l.take(3).joinToString(", ") + (if(l.size > 3) "  +${l.size - 3} more" else "")
}
data class LedgerEntry(val id: Long, val date: String, val party: String, val type: String, val amount: Double, val desc: String)

data class SellerInfo(val name: String, val addr: String, val phone: String, val email: String, val landmark: String,
                      val pincode: String, val gst: String, val bank: String, val ifsc: String, val juris: String,
                      val upi: String, val state: String)
data class BuyerInfo(val name: String, val addr1: String, val addr2: String, val landmark: String,
                     val pincode: String, val phone: String, val gst: String, val state: String)
data class BillCalc(
    val gross: Double, val itemDiscount: Double, val billDiscount: Double,
    val taxable: Double, val cgst: Double, val sgst: Double, val igst: Double, val totalTax: Double,
    val roundOff: Double, val grand: Double, val exchange: Double,
    val cash: Double, val upi: Double, val card: Double, val paid: Double, val balance: Double,
    val interState: Boolean
)

// Single place where a bill is added up, so the screen and the PDF can never disagree.
// Rates are tax-inclusive: each line is split into taxable value and tax, item discount is
// applied before the split, and IGST replaces CGST+SGST when the buyer's state differs.
fun computeBill(items: List<InvItem>, billDiscount: Double, exchange: Double, cash: Double, upi: Double, card: Double,
                sellerState: String, buyerState: String): BillCalc {
    var gross = 0.0; var itemDisc = 0.0; var taxable = 0.0; var tax = 0.0
    for(it in items) {
        val g = it.qty * it.rate
        val d = g * (it.discount.coerceIn(0.0, 100.0) / 100.0)
        val net = g - d
        val tr = it.taxRate.coerceIn(0.0, 100.0)
        val base = net / (1 + tr / 100.0)
        gross += g; itemDisc += d; taxable += base; tax += (net - base)
    }
    val bd = billDiscount.coerceIn(0.0, taxable + tax)
    // The bill discount is spread over taxable value and tax in proportion, so the
    // printed rows add up: Gross - Discount = Taxable Value + CGST + SGST.
    val netBeforeBillDisc = taxable + tax
    val keepFactor = if(netBeforeBillDisc > 0.0) (netBeforeBillDisc - bd) / netBeforeBillDisc else 0.0
    val taxableAfter = taxable * keepFactor
    val taxAfter = tax * keepFactor
    val beforeRound = taxableAfter + taxAfter
    val rounded = Math.round(beforeRound).toDouble()
    val roundOff = rounded - beforeRound
    val paid = cash + upi + card
    val interState = sellerState.isNotBlank() && buyerState.isNotBlank() && sellerState.trim() != buyerState.trim()
    val grand = rounded - exchange
    return BillCalc(gross, itemDisc, bd, taxableAfter,
        if(interState) 0.0 else taxAfter / 2, if(interState) 0.0 else taxAfter / 2, if(interState) taxAfter else 0.0, taxAfter,
        roundOff, grand, exchange, cash, upi, card, paid, grand - paid, interState)
}

fun fmt(v: Double): String = String.format("%.2f", v)

// Indian financial year label, e.g. "25-26" for bills dated after 1 April 2025.
fun fyLabel(): String {
    val c = Calendar.getInstance()
    val start = if(c.get(Calendar.MONTH) + 1 >= 4) c.get(Calendar.YEAR) else c.get(Calendar.YEAR) - 1
    return String.format("%02d-%02d", start % 100, (start + 1) % 100)
}

fun nextInvoiceNo(prefs: android.content.SharedPreferences): String {
    val prefix = (prefs.getString("sPrefix", "") ?: "").trim().ifEmpty { "INV" }
    val n = prefs.getInt("nextInv", 1)
    return "$prefix/${fyLabel()}/" + String.format("%03d", n)
}

fun bumpInvoiceNo(prefs: android.content.SharedPreferences) {
    prefs.edit().putInt("nextInv", prefs.getInt("nextInv", 1) + 1).apply()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF2E7D32))) { MainAppScreen() } }
    }
}

@Composable
fun MainAppScreen() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("penkhata_data", Context.MODE_PRIVATE) }
    var savedPin by remember { mutableStateOf(prefs.getString("appPin", "") ?: "") }
    var isLocked by remember { mutableStateOf(savedPin.isNotEmpty()) }
    var currentScreen by remember { mutableStateOf("dashboard") }
    // bumped after a restore or a reset: every screen rebuilds and re-reads its data
    var dataVersion by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { importJsonIfNeeded(ctx) }; dataVersion++ }

    var showExit by remember { mutableStateOf(false) }
    var exiting by remember { mutableStateOf(false) }
    val exitScope = rememberCoroutineScope()
    // Back used to minimise silently; now it asks, and saves a backup on the way out.
    BackHandler(enabled = !isLocked) { showExit = true }
    if(showExit) AlertDialog(
        onDismissRequest = { showExit = false },
        title = { Text("Exit PenKhata?") },
        text = { Text("A backup is saved first, so nothing is lost. Bills, stock and settings stay on this phone.") },
        confirmButton = { TextButton(onClick = {
            if(!exiting) {
                exiting = true
                exitScope.launch {
                    val f = withContext(Dispatchers.IO) { autoBackup(ctx) }
                    exiting = false
                    showExit = false
                    Toast.makeText(ctx, if(f != null) "Backup saved: " + f.name else "Exit without backup (could not write it)", Toast.LENGTH_LONG).show()
                    (ctx as? android.app.Activity)?.finish()
                }
            }
        }) { Text(if(exiting) "SAVING..." else "SAVE & EXIT") } },
        dismissButton = { TextButton(onClick = { showExit = false }) { Text("CANCEL") } }
    )
    if (isLocked) LoginScreen(savedPin) { isLocked = false }
    else Scaffold(
        bottomBar = { NavigationBar {
            NavigationBarItem(selected = currentScreen=="dashboard", onClick = { currentScreen="dashboard" }, icon = { Icon(Icons.Default.EditNote, "Bill") }, label = { Text("Entry") })
            NavigationBarItem(selected = currentScreen=="ledger", onClick = { currentScreen="ledger" }, icon = { Icon(Icons.Default.AccountBalanceWallet, "Ledger") }, label = { Text("Ledger") })
            NavigationBarItem(selected = currentScreen=="stock", onClick = { currentScreen="stock" }, icon = { Icon(Icons.Default.Inventory, "Stock") }, label = { Text("Stock") })
            NavigationBarItem(selected = currentScreen=="reports", onClick = { currentScreen="reports" }, icon = { Icon(Icons.Default.Assessment, "GST") }, label = { Text("GST") })
            NavigationBarItem(selected = currentScreen=="history", onClick = { currentScreen="history" }, icon = { Icon(Icons.Default.History, "Files") }, label = { Text("Files") })
            NavigationBarItem(selected = currentScreen=="settings", onClick = { currentScreen="settings" }, icon = { Icon(Icons.Default.Settings, "Set") }, label = { Text("Set") })
        }}
    ) { p -> Box(Modifier.padding(p)) { key(dataVersion) { when(currentScreen) {
        "dashboard" -> InvoiceScreen(false)
        "ledger" -> LedgerScreen()
        "stock" -> StockScreen()
        "reports" -> GSTReportScreen()
        "history" -> FileHistoryScreen()
        else -> SettingsScreen(savedPin, onDataChanged = { dataVersion++ }) { new -> prefs.edit().putString("appPin", new).apply(); savedPin = new }
    }}}}
}

@Composable
fun LoginScreen(pin: String, unlock: () -> Unit) {
    val focusManager = LocalFocusManager.current
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("LOCKED", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(value=input, onValueChange={input=it}, visualTransformation=PasswordVisualTransformation(), keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword, imeAction=ImeAction.Next), modifier=Modifier.padding(20.dp), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
        Button(onClick={if(input==pin) unlock()}) { Text("UNLOCK") }
    }
}

@Composable
fun InvoiceScreen(isQuote: Boolean) {
    val focusManager = LocalFocusManager.current
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("penkhata_data", android.content.Context.MODE_PRIVATE) }
    val sellerState = prefs.getString("sState", "21") ?: "21"
    var invNo by rememberSaveable { mutableStateOf(nextInvoiceNo(prefs)) }
    var date by rememberSaveable { mutableStateOf(SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH).format(Date())) }
    var payMode by rememberSaveable { mutableStateOf("") }; var delNote by rememberSaveable { mutableStateOf("") }
    var bName by rememberSaveable { mutableStateOf("") }
    var bAddr1 by rememberSaveable { mutableStateOf("") }; var bAddr2 by rememberSaveable { mutableStateOf("") }
    var bLandmark by rememberSaveable { mutableStateOf("") }; var bPincode by rememberSaveable { mutableStateOf("") }
    var bPhone by rememberSaveable { mutableStateOf("") }
    var bGst by rememberSaveable { mutableStateOf("") }; var bState by rememberSaveable { mutableStateOf("") }
    var iDesc by rememberSaveable { mutableStateOf("") }; var iSerial by rememberSaveable { mutableStateOf("") }
    var iHsn by rememberSaveable { mutableStateOf("") }; var iQty by rememberSaveable { mutableStateOf("1") }
    var iRate by rememberSaveable { mutableStateOf("") }; var iUnit by rememberSaveable { mutableStateOf("pcs") }
    var iTax by rememberSaveable { mutableStateOf(prefs.getString("defTax", "18") ?: "18") }
    var iDisc by rememberSaveable { mutableStateOf("0") }
    var items by rememberSaveable(stateSaver = InvItemListSaver) { mutableStateOf(listOf<InvItem>()) }
    var billDisc by rememberSaveable { mutableStateOf("") }; var exchange by rememberSaveable { mutableStateOf("") }
    var cash by rememberSaveable { mutableStateOf("") }; var upi by rememberSaveable { mutableStateOf("") }
    var card by rememberSaveable { mutableStateOf("") }; var finance by rememberSaveable { mutableStateOf("") }
    val (stockList, _) = loadState(emptyList<StockEntry>(), ctx) { withContext(Dispatchers.IO) { loadStock(ctx) } }
    var showStock by rememberSaveable { mutableStateOf(false) }
    // one row per handset: a stock line holding 3 IMEIs offers 3 choices
    val stockUnits: List<Pair<StockEntry, String>> = stockList.filter { it.qty > 0 }.flatMap { e ->
        val ims = e.imeiList()
        if(ims.isEmpty()) listOf(e to "") else ims.map { e to it }
    }
    val scanClient = remember { GmsBarcodeScanning.getClient(ctx, GmsBarcodeScannerOptions.Builder().build()) }
    val (customers, _) = loadState(emptyList<BuyerInfo>(), ctx) { withContext(Dispatchers.IO) { loadCustomers(ctx) } }
    fun applyStockByImei(imeiNo: String) {
        val e = findStockByImei(stockList, imeiNo) ?: return
        iDesc = e.model
        iHsn = e.hsn
        if(e.mrp > 0.0) iRate = if(e.mrp == e.mrp.toLong().toDouble()) e.mrp.toLong().toString() else e.mrp.toString()
        iQty = "1"
        Toast.makeText(ctx, "Filled from stock: " + e.model, Toast.LENGTH_SHORT).show()
    }
    fun applyCustomerByPhone(phoneNo: String) {
        val c = findCustomerByPhone(customers, phoneNo) ?: return
        bName = c.name; bAddr1 = c.addr1; bAddr2 = c.addr2; bLandmark = c.landmark
        bPincode = c.pincode; bGst = c.gst; bState = c.state
        Toast.makeText(ctx, "Customer details filled: " + c.name, Toast.LENGTH_SHORT).show()
    }
    var pendingScan by remember { mutableStateOf<File?>(null) }
    val scanCamera = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val f = pendingScan
        if(res.resultCode == android.app.Activity.RESULT_OK && f != null && f.exists()) {
            decodeBarcodeFromImage(ctx, f) { v -> iSerial = v; applyStockByImei(v) }
        }
        pendingScan = null
    }
    fun startImeiScan() {
        if(playScannerAvailable(ctx)) {
            try {
                scanClient.startScan()
                    .addOnSuccessListener { b -> val v = b.rawValue; if(v != null) { iSerial = v; applyStockByImei(v) } }
                    .addOnFailureListener { e ->
                        // Play Services scanner refused: fall back to a photo
                        val f = makeScanPhotoFile(ctx); pendingScan = f
                        cameraScanIntent(ctx, f)?.let { scanCamera.launch(it) }
                            ?: Toast.makeText(ctx, "Scanner failed: " + (e.message ?: "unknown"), Toast.LENGTH_LONG).show()
                    }
            } catch(e: Exception) {
                val f = makeScanPhotoFile(ctx); pendingScan = f
                cameraScanIntent(ctx, f)?.let { scanCamera.launch(it) }
            }
        } else {
            val f = makeScanPhotoFile(ctx); pendingScan = f
            val i = cameraScanIntent(ctx, f)
            if(i != null) scanCamera.launch(i)
        }
    }
    var showCustomers by rememberSaveable { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    fun clearBill() {
        invNo = nextInvoiceNo(prefs)
        bName=""; bAddr1=""; bAddr2=""; bLandmark=""; bPincode=""; bPhone=""; bGst=""; bState=""
        items = emptyList()
        billDisc=""; exchange=""; cash=""; upi=""; card=""; finance=""
        payMode=""; delNote=""
    }

    // Pay Mode follows the Cash / UPI / Card amounts, so it never contradicts them.
    val autoPayMode = listOf(
        if((cash.toDoubleOrNull() ?: 0.0) > 0.0) "CASH" else "",
        if((upi.toDoubleOrNull() ?: 0.0) > 0.0) "UPI" else "",
        if((card.toDoubleOrNull() ?: 0.0) > 0.0) "CARD" else ""
    ).filter{it.isNotBlank()}.joinToString(", ")
    LaunchedEffect(autoPayMode) { if(autoPayMode.isNotBlank() && payMode != autoPayMode) payMode = autoPayMode }

    val calc = computeBill(items, billDisc.toDoubleOrNull() ?: 0.0, exchange.toDoubleOrNull() ?: 0.0,
        cash.toDoubleOrNull() ?: 0.0, upi.toDoubleOrNull() ?: 0.0, card.toDoubleOrNull() ?: 0.0, sellerState, bState)

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(if(isQuote) "QUOTATION" else "TAX INVOICE", fontSize = 24.sp, fontWeight = FontWeight.Bold, color=Color.Blue)
            TextButton(onClick = { clearBill(); Toast.makeText(ctx, "Cleared", Toast.LENGTH_SHORT).show() }) { Text("RESET", color = Color.Red) }
        }
        Card(Modifier.padding(vertical=5.dp)) { Column(Modifier.padding(10.dp)) {
            OutlinedTextField(invNo, {invNo=it}, label={Text("Inv No")}, modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
            OutlinedTextField(date, {date=it}, label={Text("Date")}, modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
            Row { OutlinedTextField(payMode, {payMode=it}, label={Text("Pay Mode")}, modifier=Modifier.weight(1f), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); Spacer(Modifier.width(5.dp)); OutlinedTextField(delNote, {delNote=it}, label={Text("Delivery Note")}, modifier=Modifier.weight(1f), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })) }
            Text(if(autoPayMode.isBlank()) "Pay Mode fills itself from Cash / UPI / Card below" else "Pay Mode: $autoPayMode", fontSize=10.sp, color=Color(0xFF2E7D32))
        }}
        Card(Modifier.padding(vertical=5.dp)) { Column(Modifier.padding(10.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Past Customers (${customers.size})", fontWeight = FontWeight.Bold)
                TextButton(onClick = { showCustomers = !showCustomers }) { Text(if(showCustomers) "HIDE" else "PICK") }
            }
            if(customers.isEmpty()) Text("No saved customers yet - they appear after your first bill", fontSize = 10.sp)
            if(showCustomers) customers.forEach { c ->
                Column(Modifier.fillMaxWidth().clickable {
                    bName = c.name; bAddr1 = c.addr1; bAddr2 = c.addr2; bLandmark = c.landmark
                    bPincode = c.pincode; bPhone = c.phone; bGst = c.gst; bState = c.state
                    showCustomers = false
                    Toast.makeText(ctx, "Filled from past bill", Toast.LENGTH_SHORT).show()
                }.padding(vertical = 6.dp)) {
                    Text(c.name, fontWeight = FontWeight.Bold, maxLines = 2)
                    if(c.phone.isNotBlank()) Text("Ph: ${c.phone}" + (if(c.gst.isBlank()) "" else "  \u00b7  GSTIN: ${c.gst}"), fontSize = 10.sp)
                    val addr = listOf(c.addr1, c.addr2, c.pincode).filter { it.isNotBlank() }.joinToString(", ")
                    if(addr.isNotBlank()) Text(addr, fontSize = 10.sp, maxLines = 2)
                }
                Divider()
            }
        }}
        Card(Modifier.padding(vertical=5.dp)) { Column(Modifier.padding(10.dp)) {
            Text("Buyer Details", fontWeight=FontWeight.Bold)
            OutlinedTextField(bName, {bName=it}, label={Text("Name")}, modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
            OutlinedTextField(bAddr1, {bAddr1=it}, label={Text("Address Line 1")}, modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
            OutlinedTextField(bAddr2, {bAddr2=it}, label={Text("Address Line 2")}, modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
            OutlinedTextField(bLandmark, {bLandmark=it}, label={Text("Landmark")}, modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
            OutlinedTextField(bPincode, {bPincode=it}, label={Text("Pincode")}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number, imeAction=ImeAction.Next), modifier=Modifier.fillMaxWidth(), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
            OutlinedTextField(bPhone, { bPhone = it; applyCustomerByPhone(it) }, label={Text("Phone")}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Phone, imeAction=ImeAction.Next), modifier=Modifier.fillMaxWidth(), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
            Row { OutlinedTextField(bGst, {bGst=it}, label={Text("GSTIN")}, modifier=Modifier.weight(1f), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); Spacer(Modifier.width(5.dp)); OutlinedTextField(bState, {bState=it}, label={Text("State Code")}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number, imeAction=ImeAction.Next), modifier=Modifier.weight(1f), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })) }
            if(calc.interState) Text("Other state: IGST will be charged", fontSize=10.sp, color=Color(0xFF2E7D32))
        }}
        Card(Modifier.padding(vertical=5.dp)) { Column(Modifier.padding(10.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("From Stock (${stockUnits.size})", fontWeight = FontWeight.Bold)
                TextButton(onClick = { showStock = !showStock }) { Text(if(showStock) "HIDE" else "PICK") }
            }
            if(stockUnits.isEmpty()) Text("No stock yet - add it in the Stock tab", fontSize = 10.sp)
            if(showStock) stockUnits.forEach { pair ->
                val entry = pair.first; val imeiNo = pair.second
                Column(Modifier.fillMaxWidth().clickable {
                    iDesc = entry.model
                    iSerial = imeiNo
                    iHsn = entry.hsn
                    if(entry.mrp > 0.0) iRate = if(entry.mrp == entry.mrp.toLong().toDouble()) entry.mrp.toLong().toString() else entry.mrp.toString()
                    iQty = "1"
                    showStock = false
                    Toast.makeText(ctx, "Filled from stock", Toast.LENGTH_SHORT).show()
                }.padding(vertical = 6.dp)) {
                    Text(entry.model, fontWeight = FontWeight.Bold, maxLines = 3)
                    if(imeiNo.isNotBlank()) Text("IMEI: $imeiNo", fontSize = 10.sp)
                    Text("MRP \u20b9${fmt(entry.mrp)}" + (if(entry.supplier.isBlank()) "" else "  \u00b7  ${entry.supplier}"), fontSize = 10.sp)
                }
                Divider()
            }
        }}
        Card(Modifier.padding(vertical=5.dp)) { Column(Modifier.padding(10.dp)) {
            Text("Add Item", fontWeight=FontWeight.Bold)
            OutlinedTextField(iDesc, {iDesc=it}, label={Text("Item Name")}, modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(iSerial, { iSerial = it; applyStockByImei(it) }, label={Text("Serial / IMEI")}, modifier=Modifier.weight(1f), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
                IconButton(onClick = { startImeiScan() }) { Icon(Icons.Default.QrCodeScanner, "Scan IMEI") }
            }
            Row { OutlinedTextField(iHsn, {iHsn=it}, label={Text("HSN")}, modifier=Modifier.weight(1f), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); Spacer(Modifier.width(5.dp)); OutlinedTextField(iUnit, {iUnit=it}, label={Text("Unit")}, modifier=Modifier.weight(1f), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })) }
            Row { OutlinedTextField(iQty, {iQty=it}, label={Text("Qty")}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number, imeAction=ImeAction.Next), modifier=Modifier.weight(1f), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); Spacer(Modifier.width(5.dp)); OutlinedTextField(iRate, {iRate=it}, label={Text("Rate (Inc. Tax)")}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number, imeAction=ImeAction.Next), modifier=Modifier.weight(1f), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })) }
            Row(Modifier.padding(top=5.dp)) { OutlinedTextField(iTax, {iTax=it}, label={Text("GST %")}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number, imeAction=ImeAction.Next), modifier=Modifier.weight(1f), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); Spacer(Modifier.width(5.dp)); OutlinedTextField(iDisc, {iDisc=it}, label={Text("Disc %")}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number, imeAction=ImeAction.Next), modifier=Modifier.weight(1f), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })) }
            Button(onClick={
                if(iDesc.isNotEmpty()) {
                    items = items + InvItem(iDesc, iSerial, iHsn, iQty.toDoubleOrNull() ?: 1.0, iRate.toDoubleOrNull() ?: 0.0, iUnit,
                        (iTax.toDoubleOrNull() ?: 18.0).coerceIn(0.0, 100.0), (iDisc.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 100.0))
                    iDesc=""; iSerial=""; iQty="1"; iRate=""; iDisc="0"
                }
            }, modifier=Modifier.fillMaxWidth().padding(top=5.dp)) { Text("ADD") }
        }}
        items.forEachIndexed{ idx, it ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${idx+1}. ${it.desc} (${qtyText(it.qty)} ${it.unit}) \u20b9${fmt(it.rate)}" + (if(it.discount>0.004) " -${fmt(it.discount)}%" else ""), Modifier.weight(1f))
                IconButton(onClick={ val m=items.toMutableList(); m.removeAt(idx); items=m }) { Icon(Icons.Default.Delete,"Del",tint=Color.Red) }
            }
            Divider()
        }
        Card(Modifier.padding(vertical=5.dp)) { Column(Modifier.padding(10.dp)) {
            Text("Charges & Payment", fontWeight=FontWeight.Bold)
            Row { OutlinedTextField(billDisc, {billDisc=it}, label={Text("Bill Discount \u20b9")}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number, imeAction=ImeAction.Next), modifier=Modifier.weight(1f), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); Spacer(Modifier.width(5.dp)); OutlinedTextField(exchange, {exchange=it}, label={Text("Exchange \u20b9")}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number, imeAction=ImeAction.Next), modifier=Modifier.weight(1f), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })) }
            Row { OutlinedTextField(cash, {cash=it}, label={Text("Cash \u20b9")}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number, imeAction=ImeAction.Next), modifier=Modifier.weight(1f), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); Spacer(Modifier.width(5.dp)); OutlinedTextField(upi, {upi=it}, label={Text("UPI \u20b9")}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number, imeAction=ImeAction.Next), modifier=Modifier.weight(1f), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); Spacer(Modifier.width(5.dp)); OutlinedTextField(card, {card=it}, label={Text("Card \u20b9")}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number, imeAction=ImeAction.Next), modifier=Modifier.weight(1f), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })) }
            OutlinedTextField(finance, {finance=it}, label={Text("Finance / EMI Scheme")}, modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
        }}
        Card(Modifier.padding(vertical=5.dp)) { Column(Modifier.padding(10.dp)) {
            Text("Summary", fontWeight=FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Gross Value"); Text("\u20b9${fmt(calc.gross)}") }
            val shownDiscount = calc.itemDiscount + calc.billDiscount
            if(shownDiscount>0.004) Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Less Discount"); Text("-\u20b9${fmt(shownDiscount)}") }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Taxable Value"); Text("\u20b9${fmt(calc.taxable)}") }
            if(calc.interState) Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("IGST"); Text("\u20b9${fmt(calc.igst)}") }
            else { Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("CGST"); Text("\u20b9${fmt(calc.cgst)}") }; Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("SGST"); Text("\u20b9${fmt(calc.sgst)}") } }
            if(Math.abs(calc.roundOff)>=0.005) Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Round Off"); Text("\u20b9${fmt(calc.roundOff)}") }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Grand Total", fontWeight=FontWeight.Bold); Text("\u20b9${fmt(calc.grand)}", fontWeight=FontWeight.Bold) }
            if(calc.paid>0.004) Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Paid"); Text("\u20b9${fmt(calc.paid)}") }
            if(Math.abs(calc.balance)>0.004) Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Balance Due", color=Color.Red); Text("\u20b9${fmt(calc.balance)}", color=Color.Red) }
        }}
        Spacer(Modifier.height(10.dp))
        Button(enabled = !busy, onClick={
            val sName = prefs.getString("sName", "") ?: ""
            if(sName.isEmpty()) Toast.makeText(ctx, "Setup Settings First!", Toast.LENGTH_SHORT).show()
            else if(items.isEmpty()) Toast.makeText(ctx, "Add at least one item", Toast.LENGTH_SHORT).show()
            else {
                // Snapshot everything now: the form is cleared once the PDF is written.
                val snapInv = invNo; val snapDate = date; val snapPay = payMode; val snapNote = delNote
                val snapItems = items; val snapCalc = calc
                val snapBuyer = listOf(bName, bAddr1, bAddr2, bLandmark, bPincode, bPhone, bGst, bState)
                val snapFinance = finance
                val billMs = System.currentTimeMillis()
                // snapshot the outlet details with the bill, so a reprint later shows the
                // address that was in force when the bill was made
                val sel = SellerInfo(sName,
                    listOf(prefs.getString("sAddr1","")?:"", prefs.getString("sAddr2","")?:"").filter{it.isNotBlank()}.joinToString("\n"),
                    prefs.getString("sPhone","")?:"", prefs.getString("sEmail","")?:"", prefs.getString("sLandmark","")?:"",
                    prefs.getString("sPincode","")?:"", prefs.getString("sGst","")?:"", prefs.getString("sBank","")?:"",
                    prefs.getString("sIfsc","")?:"", prefs.getString("sJuris","")?:"", prefs.getString("sUpi","")?:"",
                    prefs.getString("sState","21")?:"21")
                val buyer = BuyerInfo(snapBuyer[0], snapBuyer[1], snapBuyer[2], snapBuyer[3], snapBuyer[4], snapBuyer[5], snapBuyer[6], snapBuyer[7])
                val snapBillDisc = billDisc.toDoubleOrNull() ?: 0.0; val snapExch = exchange.toDoubleOrNull() ?: 0.0
                val snapCash = cash.toDoubleOrNull() ?: 0.0; val snapUpi = upi.toDoubleOrNull() ?: 0.0; val snapCard = card.toDoubleOrNull() ?: 0.0
                busy = true
                scope.launch {
                    val file = try {
                        withContext(Dispatchers.IO) {
                            saveInvoiceData(ctx, billMs, isQuote, snapInv, snapDate, snapPay, snapNote, buyer, sel,
                                snapBillDisc, snapExch, snapCash, snapUpi, snapCard, snapFinance, snapCalc, snapItems)
                            // no PDF is kept: this one goes to the cache only so it can be shown now
                            createPdf(ctx, isQuote, snapInv, snapDate, snapPay, snapNote, sel.name, sel.addr, sel.phone, sel.email,
                                sel.landmark, sel.pincode, sel.gst, sel.bank, sel.ifsc, sel.juris,
                                buyer.name, buyer.addr1, buyer.addr2, buyer.landmark, buyer.pincode, buyer.phone, buyer.gst, buyer.state,
                                snapItems, snapCalc, snapFinance, billMs, sel.upi, billOutDir(ctx))
                        }
                    } catch(e: Exception) {
                        null
                    }
                    busy = false
                    if(file == null) { Toast.makeText(ctx, "Could not write the PDF", Toast.LENGTH_LONG).show() }
                    else {
                        if(!isQuote) {
                            bumpInvoiceNo(prefs); clearBill()
                            scope.launch { withContext(Dispatchers.IO) { deductStock(ctx, snapItems) } }
                        }
                        openPdf(ctx, file)
                    }
                }
            }
        }, modifier=Modifier.fillMaxWidth(), colors=ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))) { Text(if(busy) "GENERATING..." else if(isQuote) "GENERATE QUOTE" else "GENERATE PDF") }
        Spacer(Modifier.height(60.dp))
    }
}

// --- SECONDARY SCREENS ---
@Composable
fun LoadingRow(text: String) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 11.sp, color = Color.Gray)
    }
}
// Loads data off the main thread and says whether it is still working, so a screen can
// show a spinner instead of an empty list on a big data set.
@Composable
fun <T> loadState(initial: T, key1: Any?, key2: Any? = null, loader: suspend () -> T): Pair<T, Boolean> {
    val state = produceState(initial to true, key1, key2) {
        value = initial to true
        value = loader() to false
    }
    return state.value
}
@Composable fun LedgerScreen() {
    val focusManager = LocalFocusManager.current
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("penkhata_data", android.content.Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var p by rememberSaveable { mutableStateOf("") }; var t by rememberSaveable { mutableStateOf("DEBIT") }
    var a by rememberSaveable { mutableStateOf("") }; var d by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }
    val (entries, loadingEntries) = loadState(emptyList<LedgerEntry>(), reload) { withContext(Dispatchers.IO) { loadLedger(ctx) } }
    val (invoices, loadingInvoices) = loadState(emptyList<InvoiceRec>(), reload) { withContext(Dispatchers.IO) { loadInvoices(ctx) } }
    val accounts = partyAccounts(entries, invoices)
    val outstanding = accounts.sumOf { it.net }
    val shown = entries.filter { filter.isBlank() || it.party.trim().equals(filter, true) }.reversed()
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("LEDGER", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        if(loadingEntries || loadingInvoices) LoadingRow("Reading ledger and bills...")
        Card(Modifier.padding(vertical = 10.dp)) { Column(Modifier.padding(16.dp)) {
            Text("Outstanding", fontWeight = FontWeight.Bold)
            Divider(Modifier.padding(vertical = 5.dp))
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Text("Total receivable")
                Text("\u20b9${fmt(outstanding)}", fontWeight = FontWeight.Bold, color = if(outstanding > 0.004) Color.Red else Color(0xFF2E7D32))
            }
            Text("Manual khata plus unpaid balances on bills", fontSize = 10.sp)
        }}
        if(accounts.isNotEmpty()) Card(Modifier.padding(vertical = 5.dp)) { Column(Modifier.padding(16.dp)) {
            Text("Parties", fontWeight = FontWeight.Bold)
            Divider(Modifier.padding(vertical = 5.dp))
            accounts.forEach { acc ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { filter = if(filter == acc.party) "" else acc.party }) {
                        Text(acc.party, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text("Bills due \u20b9${fmt(acc.billDue)}  \u00b7  khata \u20b9${fmt(acc.manual)}", fontSize = 10.sp)
                    }
                    Text("\u20b9${fmt(acc.net)}", color = if(acc.net > 0.004) Color.Red else Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                    if(acc.net > 0.004) IconButton(onClick = { shareReminder(ctx, acc.party, acc.net, prefs.getString("sName","") ?: "") }) {
                        Icon(Icons.Default.Share, "Remind")
                    }
                }
                Divider()
            }
        }}
        Card(Modifier.padding(vertical = 5.dp)) { Column(Modifier.padding(10.dp)) {
            Text("Add Entry", fontWeight = FontWeight.Bold)
            OutlinedTextField(p, {p=it}, label={Text("Party")}, modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
            Row {
                Button(onClick={t="DEBIT"}, colors=ButtonDefaults.buttonColors(containerColor=if(t=="DEBIT")Color.Red else Color.Gray), modifier=Modifier.weight(1f)){Text("OWES (DEBIT)")}
                Spacer(Modifier.width(5.dp))
                Button(onClick={t="CREDIT"}, colors=ButtonDefaults.buttonColors(containerColor=if(t=="CREDIT")Color.Green else Color.Gray), modifier=Modifier.weight(1f)){Text("PAID (CREDIT)")}
            }
            OutlinedTextField(a, {a=it}, label={Text("Amount")}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number, imeAction=ImeAction.Next), modifier=Modifier.fillMaxWidth(), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
            OutlinedTextField(d, {d=it}, label={Text("Note")}, modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
            Button(onClick={
                val amt = a.toDoubleOrNull() ?: 0.0
                if(p.isNotBlank() && amt > 0.0) {
                    val en = LedgerEntry(System.currentTimeMillis(), SimpleDateFormat("dd-MM", Locale.ENGLISH).format(Date()), p.trim(), t, amt, d.trim())
                    scope.launch {
                        withContext(Dispatchers.IO) { saveLedgerEntry(ctx, en) }
                        reload++; p=""; a=""; d=""
                    }
                }
            }, modifier=Modifier.fillMaxWidth()) { Text("ADD") }
        }}
        if(filter.isNotBlank()) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Showing: $filter", fontWeight = FontWeight.Bold)
                TextButton(onClick = { filter = "" }) { Text("SHOW ALL") }
            }
        }
        shown.forEach {
            Row(Modifier.padding(8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(it.party, fontWeight=FontWeight.Bold)
                    Text(if(it.desc.isBlank()) it.date else it.date + "  \u00b7  " + it.desc, fontSize=10.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(if(it.type=="DEBIT") "OWES" else "PAID", color=if(it.type=="DEBIT") Color.Red else Color.Green, fontSize=10.sp)
                    Text("\u20b9${fmt(it.amount)}", fontWeight=FontWeight.Bold)
                }
                IconButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { File(ctx.filesDir, "led_${it.id}.json").delete() }
                        reload++
                    }
                }) { Icon(Icons.Default.Delete, "Del", tint = Color.Gray) }
            }
            Divider()
        }
        Spacer(Modifier.height(50.dp))
    }
}

@Composable fun StockScreen() {
    val focusManager = LocalFocusManager.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableStateOf(0) }
    val (stock, loadingStock) = loadState(emptyList<StockEntry>(), reload) { withContext(Dispatchers.IO) { loadStock(ctx) } }
    var model by rememberSaveable { mutableStateOf("") }; var imei by rememberSaveable { mutableStateOf("") }
    var hsn by rememberSaveable { mutableStateOf("") }; var cost by rememberSaveable { mutableStateOf("") }
    var mrp by rememberSaveable { mutableStateOf("") }; var qty by rememberSaveable { mutableStateOf("1") }
    var supplier by rememberSaveable { mutableStateOf("") }
    val stockScan = remember { GmsBarcodeScanning.getClient(ctx, GmsBarcodeScannerOptions.Builder().build()) }
    var pendingStockScan by remember { mutableStateOf<File?>(null) }
    val stockCamera = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val f = pendingStockScan
        if(res.resultCode == android.app.Activity.RESULT_OK && f != null && f.exists()) {
            decodeBarcodeFromImage(ctx, f) { v -> imei = if(imei.isBlank()) v else imei.trimEnd() + "\n" + v }
        }
        pendingStockScan = null
    }
    fun startStockScan() {
        if(playScannerAvailable(ctx)) {
            try {
                stockScan.startScan()
                    .addOnSuccessListener { b -> val v = b.rawValue; if(v != null) imei = if(imei.isBlank()) v else imei.trimEnd() + "\n" + v }
                    .addOnFailureListener {
                        val f = makeScanPhotoFile(ctx); pendingStockScan = f
                        cameraScanIntent(ctx, f)?.let { stockCamera.launch(it) }
                    }
            } catch(e: Exception) {
                val f = makeScanPhotoFile(ctx); pendingStockScan = f
                cameraScanIntent(ctx, f)?.let { stockCamera.launch(it) }
            }
        } else {
            val f = makeScanPhotoFile(ctx); pendingStockScan = f
            val i = cameraScanIntent(ctx, f)
            if(i != null) stockCamera.launch(i)
        }
    }
    val totalUnits = stock.sumOf { it.qty }
    val totalValue = stock.sumOf { it.cost * it.qty }
    val lowCount = stock.count { it.qty <= 1 }
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("STOCK", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        if(loadingStock) LoadingRow("Reading stock...")
        Card(Modifier.padding(vertical = 10.dp)) { Column(Modifier.padding(10.dp)) {
            Text("Add Stock", fontWeight = FontWeight.Bold)
            OutlinedTextField(model, {model=it}, label={Text("Model / Item Name")}, modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(imei, {imei=it}, label={Text("IMEI / Serial - one per line (optional)")}, minLines=2, modifier=Modifier.weight(1f), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
                IconButton(onClick = { startStockScan() }) { Icon(Icons.Default.QrCodeScanner, "Scan IMEI") }
            }
            Row { OutlinedTextField(hsn, {hsn=it}, label={Text("HSN")}, modifier=Modifier.weight(1f), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); Spacer(Modifier.width(5.dp)); OutlinedTextField(qty, {qty=it}, label={Text("Qty")}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number, imeAction=ImeAction.Next), modifier=Modifier.weight(1f), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })) }
            Row { OutlinedTextField(cost, {cost=it}, label={Text("Purchase \u20b9")}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number, imeAction=ImeAction.Next), modifier=Modifier.weight(1f), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); Spacer(Modifier.width(5.dp)); OutlinedTextField(mrp, {mrp=it}, label={Text("MRP \u20b9")}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number, imeAction=ImeAction.Next), modifier=Modifier.weight(1f), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })) }
            OutlinedTextField(supplier, {supplier=it}, label={Text("Supplier")}, modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
            Button(onClick = {
                if(model.isNotBlank()) {
                    val imeis = imei.split("\n", ",", " ").map{ it.trim() }.filter{ it.isNotBlank() }.distinct()
                    // one IMEI = one handset, so the IMEI count wins over the typed quantity
                    val units = if(imeis.isNotEmpty()) imeis.size else (qty.toIntOrNull() ?: 1).coerceAtLeast(0)
                    val entry = StockEntry(System.currentTimeMillis(), model.trim(), imeis.joinToString("\n"), hsn.trim(),
                        cost.toDoubleOrNull() ?: 0.0, mrp.toDoubleOrNull() ?: 0.0, units,
                        supplier.trim(), SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH).format(Date()))
                    scope.launch {
                        withContext(Dispatchers.IO) { saveStock(ctx, loadStock(ctx) + entry) }
                        reload++
                        model=""; imei=""; hsn=""; cost=""; mrp=""; qty="1"; supplier=""
                        Toast.makeText(ctx, "Added to stock", Toast.LENGTH_SHORT).show()
                    }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("ADD TO STOCK") }
        }}
        Card(Modifier.padding(vertical = 10.dp)) { Column(Modifier.padding(16.dp)) {
            Text("Summary", fontWeight = FontWeight.Bold)
            Divider(Modifier.padding(vertical = 5.dp))
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Models"); Text("${stock.size}") }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Units in hand"); Text("$totalUnits") }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Stock value (cost)"); Text("\u20b9${fmt(totalValue)}") }
            if(lowCount > 0) Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Low stock (1 or less)", color = Color.Red); Text("$lowCount", color = Color.Red) }
        }}
        stock.sortedBy { it.model.lowercase() }.forEach { e ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(e.model, fontWeight = FontWeight.Bold, maxLines = 2)
                    if(e.imeiLabel().isNotBlank()) Text(e.imeiLabel(), fontSize = 10.sp, maxLines = 2)
                    Text("Cost \u20b9${fmt(e.cost)}  \u00b7  MRP \u20b9${fmt(e.mrp)}", fontSize = 10.sp, maxLines = 1)
                    if(e.supplier.isNotBlank()) Text(e.supplier, fontSize = 10.sp, maxLines = 1)
                }
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(56.dp)) {
                    Text("Qty ${e.qty}", color = if(e.qty <= 1) Color.Red else Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                    if(e.qty <= 1) Text("LOW", fontSize = 10.sp, color = Color.Red)
                }
                IconButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { saveStock(ctx, loadStock(ctx).filter { it.id != e.id }) }
                        reload++
                    }
                }) { Icon(Icons.Default.Delete, "Del", tint = Color.Red) }
            }
            Divider()
        }
        Spacer(Modifier.height(50.dp))
    }
}
@Composable fun GSTReportScreen() {
    val focusManager = LocalFocusManager.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var offset by rememberSaveable { mutableStateOf(0) }
    var reload by remember { mutableStateOf(0) }
    val (invoices, loadingInvoices) = loadState(emptyList<InvoiceRec>(), reload) { withContext(Dispatchers.IO) { loadInvoices(ctx) } }
    val (expenses, _) = loadState(emptyList<Expense>(), reload) { withContext(Dispatchers.IO) { loadExpenses(ctx) } }
    var expNote by rememberSaveable { mutableStateOf("") }
    var expAmount by rememberSaveable { mutableStateOf("") }
    val (startMs, endMs) = monthRange(offset)
    val monthInv = invoices.filter { it.whenMs in startMs until endMs }.sortedBy { it.whenMs }
    val monthExp = expenses.filter { it.ms in startMs until endMs }
    val grossSales = monthInv.sumOf { it.total }
    val taxable = monthInv.sumOf { it.taxable }
    val cgst = monthInv.sumOf { it.cgst }
    val sgst = monthInv.sumOf { it.sgst }
    val igst = monthInv.sumOf { it.igst }
    val totalTax = monthInv.sumOf { it.tax }
    val profit = monthInv.sumOf { it.profit }
    val expenseTotal = monthExp.sumOf { it.amount }
    val b2b = monthInv.filter { it.buyerGst.isNotBlank() }
    val b2c = monthInv.filter { it.buyerGst.isBlank() }
    val rateBuckets = mutableMapOf<Double, Pair<Double, Double>>()
    monthInv.forEach { rec -> rec.rates.forEach { (r, v) ->
        val prev = rateBuckets[r] ?: (0.0 to 0.0)
        rateBuckets[r] = (prev.first + v.first) to (prev.second + v.second)
    } }
    val dayTotals = monthInv.groupBy { SimpleDateFormat("dd", Locale.ENGLISH).format(Date(it.whenMs)) }.toSortedMap()

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("REPORTS", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        if(loadingInvoices) LoadingRow("Reading bills...")
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { offset -= 1 }) { Text("\u2039  Prev") }
            Text(monthLabel(offset), fontWeight = FontWeight.Bold)
            TextButton(enabled = offset < 0, onClick = { offset += 1 }) { Text("Next  \u203a") }
        }
        Card(Modifier.padding(vertical = 5.dp)) { Column(Modifier.padding(16.dp)) {
            Text("Month summary", fontWeight = FontWeight.Bold)
            Divider(Modifier.padding(vertical = 5.dp))
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Bills"); Text("${monthInv.size}") }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Sales (incl. tax)"); Text("\u20b9${fmt(grossSales)}") }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Taxable value"); Text("\u20b9${fmt(taxable)}") }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("CGST"); Text("\u20b9${fmt(cgst)}") }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("SGST"); Text("\u20b9${fmt(sgst)}") }
            if(igst > 0.004) Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("IGST"); Text("\u20b9${fmt(igst)}") }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Total tax", fontWeight = FontWeight.Bold); Text("\u20b9${fmt(totalTax)}", fontWeight = FontWeight.Bold) }
        }}
        Card(Modifier.padding(vertical = 5.dp)) { Column(Modifier.padding(16.dp)) {
            Text("B2B / B2C", fontWeight = FontWeight.Bold)
            Divider(Modifier.padding(vertical = 5.dp))
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("B2B (buyer GSTIN)"); Text("${b2b.size} bills  \u20b9${fmt(b2b.sumOf { it.total })}") }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("B2C"); Text("${b2c.size} bills  \u20b9${fmt(b2c.sumOf { it.total })}") }
        }}
        if(rateBuckets.isNotEmpty()) Card(Modifier.padding(vertical = 5.dp)) { Column(Modifier.padding(16.dp)) {
            Text("Rate-wise", fontWeight = FontWeight.Bold)
            Divider(Modifier.padding(vertical = 5.dp))
            rateBuckets.toSortedMap().forEach { (rate, v) ->
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    Text("GST ${fmt(rate)}%")
                    Text("\u20b9${fmt(v.first)}  tax \u20b9${fmt(v.second)}")
                }
            }
        }}
        Card(Modifier.padding(vertical = 5.dp)) { Column(Modifier.padding(16.dp)) {
            Text("Profit & expenses", fontWeight = FontWeight.Bold)
            Divider(Modifier.padding(vertical = 5.dp))
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Gross profit (taxable - cost)"); Text("\u20b9${fmt(profit)}") }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Expenses"); Text("\u20b9${fmt(expenseTotal)}") }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Net", fontWeight = FontWeight.Bold); Text("\u20b9${fmt(profit - expenseTotal)}", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(8.dp))
            Row { OutlinedTextField(expNote, {expNote=it}, label={Text("Expense note")}, modifier=Modifier.weight(1f), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); Spacer(Modifier.width(5.dp)); OutlinedTextField(expAmount, {expAmount=it}, label={Text("\u20b9")}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number, imeAction=ImeAction.Next), modifier=Modifier.width(90.dp), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })) }
            Button(onClick = {
                val amt = expAmount.toDoubleOrNull() ?: 0.0
                if(expNote.isNotBlank() && amt > 0.0) {
                    scope.launch {
                        withContext(Dispatchers.IO) { saveExpense(ctx, expNote.trim(), amt) }
                        expNote = ""; expAmount = ""; reload++
                    }
                }
            }, modifier = Modifier.fillMaxWidth().padding(top = 5.dp)) { Text("ADD EXPENSE") }
        }}
        if(dayTotals.isNotEmpty()) Card(Modifier.padding(vertical = 5.dp)) { Column(Modifier.padding(16.dp)) {
            Text("Day book", fontWeight = FontWeight.Bold)
            Divider(Modifier.padding(vertical = 5.dp))
            dayTotals.forEach { (day, list) ->
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    Text("Day $day  (${list.size} bill${if(list.size == 1) "" else "s"})")
                    Text("\u20b9${fmt(list.sumOf { it.total })}")
                }
            }
        }}
        Spacer(Modifier.height(40.dp))
    }
}

@Composable fun FileHistoryScreen() {
    val focusManager = LocalFocusManager.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val (bills, loadingBills) = loadState(emptyList<InvoiceRec>(), reload) {
        withContext(Dispatchers.IO) { loadInvoices(ctx, true) }
    }
    val (hits, loadingHits) = loadState(emptyList<BillHit>(), query, reload) {
        withContext(Dispatchers.IO) { if(query.trim().length >= 2) searchBills(ctx, query) else emptyList() }
    }
    fun openBill(ms: Long) {
        scope.launch {
            busy = true
            val f = withContext(Dispatchers.IO) { renderBill(ctx, ms) }
            busy = false
            if(f != null) openPdf(ctx, f) else Toast.makeText(ctx, "This bill has no item details stored (saved by an older version), so it cannot be rebuilt", Toast.LENGTH_LONG).show()
        }
    }
    fun shareBill(ms: Long) {
        scope.launch {
            busy = true
            val f = withContext(Dispatchers.IO) { renderBill(ctx, ms) }
            busy = false
            if(f != null) sharePdf(ctx, f) else Toast.makeText(ctx, "This bill has no item details stored (saved by an older version), so it cannot be rebuilt", Toast.LENGTH_LONG).show()
        }
    }
    Column(Modifier.padding(16.dp)) {
        Text("FILES", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(query, {query=it}, label={Text("Search IMEI, phone, buyer or invoice no")},
            leadingIcon = { Icon(Icons.Default.Search, "Search") }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
        if(busy) LoadingRow("Building the PDF...")
        else if(loadingBills || loadingHits) LoadingRow("Reading bills...")
        if(query.trim().length >= 2) {
            Text(if(hits.isEmpty()) "No bill matches" else "${hits.size} bill(s) match", fontSize = 10.sp)
            LazyColumn {
                items(hits) { hit ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                            Text(hit.rec.invNo, fontWeight = FontWeight.Bold)
                            Text("\u20b9${fmt(hit.rec.total)}", fontWeight = FontWeight.Bold)
                        }
                        Text(SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH).format(Date(hit.rec.whenMs)) +
                             (if(hit.rec.buyer.isBlank()) "" else "  \u00b7  " + hit.rec.buyer), fontSize = 10.sp)
                        if(hit.serial.isNotBlank()) Text("IMEI: ${hit.serial}", fontSize = 10.sp, color = Color(0xFF2E7D32))
                        Row {
                            TextButton(onClick = { openBill(hit.rec.whenMs) }) { Text("VIEW", fontSize = 10.sp) }
                            TextButton(onClick = { shareBill(hit.rec.whenMs) }) { Text("SHARE", fontSize = 10.sp) }
                        }
                    }
                    Divider()
                }
            }
        } else {
            Text("${bills.size} bill(s) - PDFs are built when you view or share", fontSize = 10.sp)
            LazyColumn {
                items(bills) { rec ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                            Text(rec.invNo, fontWeight = FontWeight.Bold)
                            Text("\u20b9${fmt(rec.total)}", fontWeight = FontWeight.Bold)
                        }
                        Text(SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH).format(Date(rec.whenMs)) +
                             (if(rec.buyer.isBlank()) "" else "  \u00b7  " + rec.buyer), fontSize = 10.sp)
                        Row {
                            TextButton(onClick = { openBill(rec.whenMs) }) { Text("VIEW", fontSize = 10.sp) }
                            TextButton(onClick = { shareBill(rec.whenMs) }) { Text("SHARE", fontSize = 10.sp) }
                        }
                    }
                    Divider()
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = {
            scope.launch {
                val n = withContext(Dispatchers.IO) { freeStoredPdfs(ctx) }
                reload++
                Toast.makeText(ctx, if(n == 0) "Nothing stored to clear" else "Removed $n stored PDF(s)", Toast.LENGTH_LONG).show()
            }
        }) { Text("Free space - remove stored PDFs", fontSize = 10.sp) }
    }
}

@Composable fun SettingsScreen(currentPin: String, onDataChanged: () -> Unit, onPinSave: (String)->Unit) {
    val focusManager = LocalFocusManager.current
    val ctx=LocalContext.current
    val prefs=remember{ctx.getSharedPreferences("penkhata_data",Context.MODE_PRIVATE)}
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if(uri != null) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { backupZip(ctx, it) }
                Toast.makeText(ctx, "Backup saved", Toast.LENGTH_LONG).show()
            } catch(e: Exception) {
                Toast.makeText(ctx, "Backup failed: " + (e.message ?: "unknown error"), Toast.LENGTH_LONG).show()
            }
        }
    }
    val scope = rememberCoroutineScope()
    var confirmMaster by remember { mutableStateOf(false) }
    var confirmAll by remember { mutableStateOf(false) }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null) {
            try {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                val isZip = bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
                val msg = if(isZip) restoreZip(ctx, ByteArrayInputStream(bytes)) else restoreJson(ctx, String(bytes, Charsets.UTF_8))
                // the restored files must also reach the database, not just the folder
                scope.launch {
                    withContext(Dispatchers.IO) { importJsonIfNeeded(ctx) }
                    onDataChanged()
                }
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            } catch(e: Exception) {
                Toast.makeText(ctx, "Restore failed: " + (e.message ?: "unknown error"), Toast.LENGTH_LONG).show()
            }
        }
    }
    var sName by remember{mutableStateOf(prefs.getString("sName","")?:"")}; var sState by remember{mutableStateOf(prefs.getString("sState","21")?:"21")}; var sPrefix by remember{mutableStateOf(prefs.getString("sPrefix","INV")?:"INV")}; var sAddr1 by remember{mutableStateOf(prefs.getString("sAddr1","")?:(prefs.getString("sAddr","")?:"").lineSequence().firstOrNull()?.trim()?:"")}; var sAddr2 by remember{mutableStateOf(prefs.getString("sAddr2","")?:(prefs.getString("sAddr","")?:"").lineSequence().drop(1).joinToString(" ").trim())}; var sPhone by remember{mutableStateOf(prefs.getString("sPhone","")?:"")}; var sEmail by remember{mutableStateOf(prefs.getString("sEmail","")?:"")}; var sLandmark by remember{mutableStateOf(prefs.getString("sLandmark","")?:"")}; var sPincode by remember{mutableStateOf(prefs.getString("sPincode","")?:"")}; var sUpi by remember{mutableStateOf(prefs.getString("sUpi","")?:"")}; var sGst by remember{mutableStateOf(prefs.getString("sGst","")?:"")}; var sBank by remember{mutableStateOf(prefs.getString("sBank","")?:"")}; var sIfsc by remember{mutableStateOf(prefs.getString("sIfsc","")?:"")}; var sJuris by remember{mutableStateOf(prefs.getString("sJuris","")?:"")}; var defTax by remember{mutableStateOf(prefs.getString("defTax","18")?:"18")}; var newPin by remember{mutableStateOf(currentPin)}; Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())){Text("SETTINGS",fontSize=24.sp,fontWeight=FontWeight.Bold); OutlinedTextField(newPin,{newPin=it},label={Text("Set Login PIN")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword, imeAction=ImeAction.Next),modifier=Modifier.fillMaxWidth(), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); OutlinedTextField(sName,{sName=it},label={Text("Firm Name")},modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); OutlinedTextField(sState,{sState=it},label={Text("My State Code (GST, e.g. 21)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number, imeAction=ImeAction.Next),modifier=Modifier.fillMaxWidth(), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); OutlinedTextField(sPrefix,{sPrefix=it},label={Text("Invoice Prefix")},modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); OutlinedTextField(sAddr1,{sAddr1=it},label={Text("Address Line 1")},modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); OutlinedTextField(sAddr2,{sAddr2=it},label={Text("Address Line 2")},modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); OutlinedTextField(sPhone,{sPhone=it},label={Text("Phone")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Phone, imeAction=ImeAction.Next),modifier=Modifier.fillMaxWidth(), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); OutlinedTextField(sEmail,{sEmail=it},label={Text("Email")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email, imeAction=ImeAction.Next),modifier=Modifier.fillMaxWidth(), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); OutlinedTextField(sLandmark,{sLandmark=it},label={Text("Landmark")},modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); OutlinedTextField(sPincode,{sPincode=it},label={Text("Pincode")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number, imeAction=ImeAction.Next),modifier=Modifier.fillMaxWidth(), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); OutlinedTextField(sUpi,{sUpi=it},label={Text("UPI ID (for the payment QR)")},modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); OutlinedTextField(sGst,{sGst=it},label={Text("GSTIN")},modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); OutlinedTextField(sBank,{sBank=it},label={Text("Bank Name & Acc")},modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); OutlinedTextField(sIfsc,{sIfsc=it},label={Text("IFSC")},modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); OutlinedTextField(sJuris,{sJuris=it},label={Text("Jurisdiction")},modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); OutlinedTextField(defTax,{defTax=it},label={Text("Default Tax %")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number, imeAction=ImeAction.Next),modifier=Modifier.fillMaxWidth(), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); Text("PenKhata "+APP_VERSION, fontSize=10.sp, color=Color.Gray, modifier=Modifier.padding(top=8.dp)); val dbBills by produceState(initialValue = -1, ctx) { value = withContext(Dispatchers.IO) { countRows(ctx, "bills") } }; val dbStock by produceState(initialValue = -1, ctx) { value = withContext(Dispatchers.IO) { countRows(ctx, "stock") } }; Text("Database: " + (if(dbBills < 0) "reading..." else "$dbBills bills, $dbStock stock lines"), fontSize=10.sp, color=Color.Gray); Button(onClick={prefs.edit().putString("sName",sName).putString("sState",sState).putString("sPrefix",sPrefix).putString("sAddr1",sAddr1).putString("sAddr2",sAddr2).putString("sPhone",sPhone).putString("sEmail",sEmail).putString("sLandmark",sLandmark).putString("sPincode",sPincode).putString("sUpi",sUpi).putString("sGst",sGst).putString("sBank",sBank).putString("sIfsc",sIfsc).putString("sJuris",sJuris).putString("defTax",defTax).apply(); onPinSave(newPin); Toast.makeText(ctx,"Saved",Toast.LENGTH_SHORT).show()},modifier=Modifier.fillMaxWidth()){Text("SAVE")}; Card(Modifier.padding(vertical=10.dp)){Column(Modifier.padding(10.dp)){ Text("Backup & Restore", fontWeight=FontWeight.Bold); Text("Saves settings, invoices, ledger and the PDF bills to one file you choose, so a reinstall does not mean re-typing everything. The app PIN is not included.", fontSize=10.sp); Spacer(Modifier.height(8.dp)); Button(onClick={ backupLauncher.launch("penkhata-backup-"+SimpleDateFormat("yyyyMMdd-HHmm", Locale.ENGLISH).format(Date())+".zip") }, modifier=Modifier.fillMaxWidth()){ Text("BACKUP DATA") }; Spacer(Modifier.height(6.dp)); Button(onClick={ restoreLauncher.launch(arrayOf("application/json","text/plain","*/*")) }, modifier=Modifier.fillMaxWidth()){ Text("RESTORE DATA") } }}; Card(Modifier.padding(vertical=10.dp)){Column(Modifier.padding(10.dp)){ Text("Reset", fontWeight=FontWeight.Bold); Text("Master reset clears bills, PDFs, customers, stock, ledger and expenses but keeps every setting on this screen. Total reset clears those as well and starts the app empty.", fontSize=10.sp); Spacer(Modifier.height(8.dp)); Button(onClick={ confirmMaster = true }, modifier=Modifier.fillMaxWidth(), colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFEF6C00))){ Text("MASTER RESET (KEEP SETTINGS)") }; Spacer(Modifier.height(6.dp)); Button(onClick={ confirmAll = true }, modifier=Modifier.fillMaxWidth(), colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFC62828))){ Text("TOTAL RESET (EVERYTHING)") } }}; if(confirmMaster) AlertDialog(onDismissRequest={ confirmMaster = false }, title={ Text("Master reset?") }, text={ Text("Deletes every bill, PDF, customer, stock line, ledger entry and expense. Your outlet details and PIN stay.") }, confirmButton={ TextButton(onClick={ scope.launch { val n = withContext(Dispatchers.IO){ wipeTransactions(ctx) }; confirmMaster = false; onDataChanged(); Toast.makeText(ctx, "Cleared $n files.", Toast.LENGTH_LONG).show() } }){ Text("DELETE") } }, dismissButton={ TextButton(onClick={ confirmMaster = false }){ Text("CANCEL") } }); if(confirmAll) AlertDialog(onDismissRequest={ confirmAll = false }, title={ Text("Total reset?") }, text={ Text("Deletes everything: bills, PDFs, customers, stock, ledger, expenses, all settings and the PIN. The app goes back to a fresh install.") }, confirmButton={ TextButton(onClick={ scope.launch { val n = withContext(Dispatchers.IO){ wipeEverything(ctx) }; confirmAll = false; onDataChanged(); Toast.makeText(ctx, "Cleared $n files and all settings. Restart the app.", Toast.LENGTH_LONG).show() } }){ Text("DELETE EVERYTHING") } }, dismissButton={ TextButton(onClick={ confirmAll = false }){ Text("CANCEL") } }); Spacer(Modifier.height(60.dp))} }

// --- LOGIC ---
data class InvoiceRec(val invNo: String, val date: String, val whenMs: Long, val buyer: String, val buyerGst: String,
                      val total: Double, val taxable: Double, val cgst: Double, val sgst: Double, val igst: Double,
                      val tax: Double, val cogs: Double, val profit: Double, val interState: Boolean,
                      val paid: Double, val balance: Double,
                      val rates: Map<Double, Pair<Double, Double>>)
data class Expense(val id: Long, val ms: Long, val note: String, val amount: Double)

fun loadInvoices(ctx: Context, includeQuotes: Boolean = false): List<InvoiceRec> {
    val out = mutableListOf<InvoiceRec>()
    ctx.filesDir.listFiles()?.filter { it.name.startsWith("inv_") }?.forEach { f ->
        val o = try { JSONObject(f.readText()) } catch(e: Exception) { null } ?: return@forEach
        if(!includeQuotes && o.optBoolean("quote", false)) return@forEach
        // older records have no timestamp: the file name carries the epoch millis
        val ms = if(o.has("ms")) o.optLong("ms") else (Regex("\\d+").find(f.name.removePrefix("inv_"))?.value?.toLongOrNull() ?: 0L)
        val rates = mutableMapOf<Double, Pair<Double, Double>>()
        o.optJSONArray("lines")?.let { arr ->
            for(i in 0 until arr.length()) {
                val it2 = arr.optJSONObject(i) ?: continue
                val r = it2.optDouble("taxRate", 0.0)
                val prev = rates[r] ?: (0.0 to 0.0)
                rates[r] = (prev.first + it2.optDouble("taxable", 0.0)) to (prev.second + it2.optDouble("tax", 0.0))
            }
        }
        out.add(InvoiceRec(o.optString("inv"), o.optString("d"), ms, o.optString("buyer"), o.optString("bgst"),
            o.optDouble("total", 0.0), o.optDouble("taxable", 0.0), o.optDouble("cgst", 0.0), o.optDouble("sgst", 0.0),
            o.optDouble("igst", 0.0), o.optDouble("tax", 0.0), o.optDouble("cogs", 0.0), o.optDouble("profit", 0.0),
            o.optBoolean("interState", false), o.optDouble("paid", 0.0), o.optDouble("balance", 0.0), rates))
    }
    // A restore (or a repeated save) can leave two files for the same bill. Keep one:
    // same invoice number, same amount, same taxable value means the same bill.
    val unique = LinkedHashMap<String, InvoiceRec>()
    for(r in out) {
        val key = r.invNo.trim() + "|" + String.format(Locale.ENGLISH, "%.2f", r.total) + "|" + String.format(Locale.ENGLISH, "%.2f", r.taxable)
        val prev = unique[key]
        if(prev == null || r.whenMs > prev.whenMs) unique[key] = r
    }
    return unique.values.toList()
}
// Every buyer seen on a saved bill, newest first, for one-tap fill on the entry screen.
fun loadCustomers(ctx: Context): List<BuyerInfo> {
    val seen = mutableListOf<BuyerInfo>()
    loadInvoices(ctx).sortedByDescending { it.whenMs }.forEach { rec ->
        val f = findBillFile(ctx, rec.whenMs) ?: return@forEach
        val o = try { JSONObject(f.readText()) } catch(e: Exception) { null } ?: return@forEach
        val b = BuyerInfo(o.optString("buyer"), o.optString("baddr1"), o.optString("baddr2"), o.optString("blmk"),
            o.optString("bpin"), o.optString("bphone"), o.optString("bgst"), o.optString("bstate"))
        if(b.name.isBlank()) return@forEach
        if(seen.none { it.name.equals(b.name, true) && it.phone == b.phone }) seen.add(b)
    }
    return seen
}

data class BillHit(val rec: InvoiceRec, val pdf: String, val serial: String)
// Searches IMEI, phone, buyer name and invoice number. Bills saved before this build
// carry no phone or serial, so they only match on name and invoice number.
fun searchBills(ctx: Context, query: String): List<BillHit> {
    val q = query.trim().lowercase()
    if(q.length < 2) return emptyList()
    val hits = mutableListOf<BillHit>()
    loadInvoices(ctx).sortedByDescending { it.whenMs }.forEach { rec ->
        val f = findBillFile(ctx, rec.whenMs) ?: return@forEach
        val o = try { JSONObject(f.readText()) } catch(e: Exception) { null } ?: return@forEach
        val phone = o.optString("bphone")
        val serials = mutableListOf<String>()
        o.optJSONArray("lines")?.let { arr ->
            for(i in 0 until arr.length()) arr.optJSONObject(i)?.optString("serial")?.takeIf { it.isNotBlank() }?.let { serials.add(it) }
        }
        val matchedSerial = serials.firstOrNull { it.lowercase().contains(q) } ?: ""
        val hit = matchedSerial.isNotBlank() || phone.lowercase().contains(q) ||
                  rec.buyer.lowercase().contains(q) || rec.invNo.lowercase().contains(q)
        if(hit) {
            val name = if(o.optBoolean("quote", false)) "Quote_${rec.whenMs}.pdf" else "Inv_${rec.whenMs}.pdf"
            hits.add(BillHit(rec, name, matchedSerial))
        }
    }
    return hits
}
// UPI payment link the customer scans; renders to a bitmap for the PDF canvas.
fun upiLink(vpa: String, name: String, amount: Double, note: String): String {
    val pn = name.ifBlank { "PenKhata" }
    return "upi://pay?pa=" + android.net.Uri.encode(vpa) + "&pn=" + android.net.Uri.encode(pn) +
           "&am=" + String.format(Locale.ENGLISH, "%.2f", amount) + "&cu=INR&tn=" + android.net.Uri.encode(note)
}
fun qrBitmap(text: String, size: Int): Bitmap? {
    return try {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for(x in 0 until size) for(y in 0 until size) {
            bmp.setPixel(x, y, if(matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
        bmp
    } catch(e: Exception) { null }
}
fun billOutDir(ctx: Context): File = File(ctx.cacheDir, "bills")

// Rebuilds the PDF from the stored record, so no PDFs need to be kept on disk.
// A bill's file is named after its timestamp when it is written, but a restore writes
// it under the restore timestamp. Look the record up by its stored "ms" as well.
fun findBillFile(ctx: Context, ms: Long): File? {
    val direct = File(ctx.filesDir, "inv_$ms.json")
    if(direct.exists()) return direct
    return ctx.filesDir.listFiles()?.firstOrNull { f ->
        f.name.startsWith("inv_") && (
            f.name.contains(ms.toString()) ||
            (try { JSONObject(f.readText()).optLong("ms") == ms } catch(e: Exception) { false })
        )
    }
}
fun renderBill(ctx: Context, ms: Long): File? {
    val o = try { JSONObject((findBillFile(ctx, ms) ?: return null).readText()) } catch(e: Exception) { null } ?: return null
    val lines = mutableListOf<InvItem>()
    o.optJSONArray("lines")?.let { arr ->
        for(i in 0 until arr.length()) {
            val l = arr.optJSONObject(i) ?: continue
            lines.add(InvItem(l.optString("desc"), l.optString("serial"), l.optString("hsn"),
                l.optDouble("qty", 1.0), l.optDouble("rate", 0.0), l.optString("unit"),
                l.optDouble("taxRate", 18.0), l.optDouble("discount", 0.0)))
        }
    }
    // bills saved before v2.1 have no line details; a blank invoice would be worse than an honest message
    if(lines.isEmpty() && o.optDouble("total", 0.0) > 0.004) return null
    val sel = o.optJSONObject("seller")
    val sellerState = sel?.optString("state") ?: ""
    val calc = computeBill(lines, o.optDouble("bd", 0.0), o.optDouble("exch", 0.0),
        o.optDouble("cash", 0.0), o.optDouble("upi", 0.0), o.optDouble("card", 0.0), sellerState, o.optString("bstate"))
    return try {
        createPdf(ctx, o.optBoolean("quote", false), o.optString("inv"), o.optString("date"),
            o.optString("payMode"), o.optString("delNote"),
            sel?.optString("name") ?: "", sel?.optString("addr") ?: "", sel?.optString("phone") ?: "", sel?.optString("email") ?: "",
            sel?.optString("landmark") ?: "", sel?.optString("pincode") ?: "", sel?.optString("gst") ?: "",
            sel?.optString("bank") ?: "", sel?.optString("ifsc") ?: "", sel?.optString("juris") ?: "",
            o.optString("buyer"), o.optString("baddr1"), o.optString("baddr2"), o.optString("blmk"), o.optString("bpin"), o.optString("bphone"),
            o.optString("bgst"), o.optString("bstate"), lines, calc, o.optString("finance"), ms,
            sel?.optString("upi") ?: "", billOutDir(ctx))
    } catch(e: Exception) { null }
}
fun viewBill(ctx: Context, ms: Long) {
    val f = renderBill(ctx, ms)
    if(f != null) openPdf(ctx, f) else Toast.makeText(ctx, "This bill has no item details stored (saved by an older version), so it cannot be rebuilt", Toast.LENGTH_LONG).show()
}
// Bills are no longer stored as PDFs; this clears any PDFs left by older builds.
fun freeStoredPdfs(ctx: Context): Int {
    var n = 0
    ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?.listFiles()?.forEach { if(it.extension.equals("pdf", true) && it.delete()) n++ }
    return n
}
fun shareBillFile(ctx: Context, ms: Long) {
    val f = renderBill(ctx, ms)
    if(f != null) sharePdf(ctx, f) else Toast.makeText(ctx, "This bill has no item details stored (saved by an older version), so it cannot be rebuilt", Toast.LENGTH_LONG).show()
}
fun sharePdf(ctx: Context, f: File) {
    val uri = try {
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", f)
    } catch(e: Exception) {
        Toast.makeText(ctx, "Could not prepare the PDF: " + (e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
        return
    }
    try {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Share bill"))
    } catch(e: ActivityNotFoundException) {
        Toast.makeText(ctx, "No app can share a PDF on this phone", Toast.LENGTH_LONG).show()
    } catch(e: Exception) {
        Toast.makeText(ctx, "Could not share the file: " + (e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
    }
}
fun openBillPdf(ctx: Context, name: String) {
    val f = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), name)
    if(f.exists()) openPdf(ctx, f) else Toast.makeText(ctx, "The PDF for this bill is no longer on the phone", Toast.LENGTH_LONG).show()
}

fun loadExpenses(ctx: Context): List<Expense> {
    val out = mutableListOf<Expense>()
    ctx.filesDir.listFiles()?.filter { it.name.startsWith("exp_") }?.forEach { f ->
        val o = try { JSONObject(f.readText()) } catch(e: Exception) { null } ?: return@forEach
        out.add(Expense(o.optLong("id"), o.optLong("ms"), o.optString("note"), o.optDouble("amount", 0.0)))
    }
    return out
}
fun saveExpense(ctx: Context, note: String, amount: Double) {
    val o = JSONObject()
    val ms = System.currentTimeMillis()
    o.put("id", ms); o.put("ms", ms); o.put("note", note); o.put("amount", amount)
    writeFileAtomic(File(ctx.filesDir, "exp_${ms}.json"), o.toString())
}
fun monthRange(offset: Int): Pair<Long, Long> {
    val c = Calendar.getInstance()
    c.set(Calendar.DAY_OF_MONTH, 1); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    c.add(Calendar.MONTH, offset)
    val start = c.timeInMillis
    c.add(Calendar.MONTH, 1)
    return start to c.timeInMillis
}
fun monthLabel(offset: Int): String {
    val c = Calendar.getInstance()
    c.set(Calendar.DAY_OF_MONTH, 1); c.add(Calendar.MONTH, offset)
    return SimpleDateFormat("MMM yyyy", Locale.ENGLISH).format(c.time)
}

// What a party owes: manual khata entries (debit - credit) plus the unpaid balance
// of their bills. A negative figure means they are in advance.
data class PartyAccount(val party: String, val manualDebit: Double, val manualCredit: Double, val billDue: Double) {
    val manual: Double get() = manualDebit - manualCredit
    val net: Double get() = manual + billDue
}
fun partyAccounts(entries: List<LedgerEntry>, invoices: List<InvoiceRec>): List<PartyAccount> {
    val names = (entries.map { it.party.trim() } + invoices.filter { it.balance > 0.004 }.map { it.buyer.trim() })
        .filter { it.isNotBlank() }.distinct()
    return names.map { name ->
        val mine = entries.filter { it.party.trim().equals(name, true) }
        PartyAccount(name,
            mine.filter { it.type == "DEBIT" }.sumOf { it.amount },
            mine.filter { it.type == "CREDIT" }.sumOf { it.amount },
            invoices.filter { it.buyer.trim().equals(name, true) }.sumOf { it.balance })
    }.sortedByDescending { it.net }
}
fun shareReminder(ctx: Context, party: String, amount: Double, firm: String) {
    val who = if(firm.isBlank()) "us" else firm
    val text = "Hello $party, your outstanding balance with $who is \u20b9" + fmt(amount) + ". Kindly clear it at your convenience. Thank you."
    try {
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
        ctx.startActivity(Intent.createChooser(send, "Send reminder"))
    } catch(e: Exception) {
        Toast.makeText(ctx, "No app to send the reminder", Toast.LENGTH_SHORT).show()
    }
}

// ---------------------------------------------------------------- database
// SQLite replaces the per-file JSON store: indexed lookups, crash-safe writes and a
// single file to back up. The JSON files are imported once and left in place.
class PenDb(ctx: Context) : android.database.sqlite.SQLiteOpenHelper(ctx, "penkhata.db", null, 1) {
    override fun onCreate(db: android.database.sqlite.SQLiteDatabase) {
        db.execSQL("""CREATE TABLE bills(ms INTEGER PRIMARY KEY, quote INTEGER, inv TEXT, date TEXT, payMode TEXT, delNote TEXT,
            buyer TEXT, bgst TEXT, baddr1 TEXT, baddr2 TEXT, blmk TEXT, bpin TEXT, bphone TEXT, bstate TEXT,
            total REAL, taxable REAL, cgst REAL, sgst REAL, igst REAL, tax REAL, interState INTEGER,
            cogs REAL, profit REAL, paid REAL, balance REAL, bd REAL, exch REAL, cash REAL, upi REAL, card REAL,
            finance TEXT, seller TEXT, lines TEXT)""")
        db.execSQL("CREATE INDEX idx_bills_inv ON bills(inv)")
        db.execSQL("CREATE INDEX idx_bills_ms ON bills(ms)")
        db.execSQL("CREATE INDEX idx_bills_buyer ON bills(buyer)")
        db.execSQL("CREATE INDEX idx_bills_phone ON bills(bphone)")
        db.execSQL("""CREATE TABLE ledger(id INTEGER PRIMARY KEY, date TEXT, party TEXT, type TEXT, amt REAL, descr TEXT)""")
        db.execSQL("CREATE INDEX idx_ledger_party ON ledger(party)")
        db.execSQL("""CREATE TABLE stock(id INTEGER PRIMARY KEY, model TEXT, imei TEXT, hsn TEXT, cost REAL, mrp REAL, qty INTEGER, supplier TEXT, addedOn TEXT)""")
        db.execSQL("CREATE INDEX idx_stock_model ON stock(model)")
        db.execSQL("""CREATE TABLE expenses(id INTEGER PRIMARY KEY, ms INTEGER, note TEXT, amount REAL)""")
        db.execSQL("""CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)""")
    }
    override fun onUpgrade(db: android.database.sqlite.SQLiteDatabase, oldVersion: Int, newVersion: Int) { }
    override fun onConfigure(db: android.database.sqlite.SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }
}

fun db(ctx: Context): PenDb = PenDb(ctx.applicationContext)

fun countRows(ctx: Context, table: String): Int {
    return try { db(ctx).readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { if(it.moveToFirst()) it.getInt(0) else 0 } } catch(e: Exception) { 0 }
}

// One-time move of the JSON files into the database. Safe to run twice: it only
// runs when the table is empty, and it never deletes the JSON.
fun importJsonIfNeeded(ctx: Context) {
    val p = ctx.getSharedPreferences("penkhata_data", Context.MODE_PRIVATE)
    val d = db(ctx)
    val w = d.writableDatabase
    w.beginTransaction()
    try {
        if(countRows(ctx, "bills") == 0) {
            ctx.filesDir.listFiles()?.filter { it.name.startsWith("inv_") }?.forEach { f ->
                val o = try { JSONObject(f.readText()) } catch(e: Exception) { null } ?: return@forEach
                val ms = if(o.has("ms")) o.optLong("ms") else (Regex("\\d+").find(f.name.removePrefix("inv_"))?.value?.toLongOrNull() ?: 0L)
                val cv = android.content.ContentValues()
                cv.put("ms", ms); cv.put("quote", if(o.optBoolean("quote", false)) 1 else 0)
                cv.put("inv", o.optString("inv")); cv.put("date", o.optString("date"))
                cv.put("payMode", o.optString("payMode")); cv.put("delNote", o.optString("delNote"))
                cv.put("buyer", o.optString("buyer")); cv.put("bgst", o.optString("bgst"))
                cv.put("baddr1", o.optString("baddr1")); cv.put("baddr2", o.optString("baddr2"))
                cv.put("blmk", o.optString("blmk")); cv.put("bpin", o.optString("bpin"))
                cv.put("bphone", o.optString("bphone")); cv.put("bstate", o.optString("bstate"))
                cv.put("total", o.optDouble("total", 0.0)); cv.put("taxable", o.optDouble("taxable", 0.0))
                cv.put("cgst", o.optDouble("cgst", 0.0)); cv.put("sgst", o.optDouble("sgst", 0.0))
                cv.put("igst", o.optDouble("igst", 0.0)); cv.put("tax", o.optDouble("tax", 0.0))
                cv.put("interState", if(o.optBoolean("interState", false)) 1 else 0)
                cv.put("cogs", o.optDouble("cogs", 0.0)); cv.put("profit", o.optDouble("profit", 0.0))
                cv.put("paid", o.optDouble("paid", 0.0)); cv.put("balance", o.optDouble("balance", 0.0))
                cv.put("bd", o.optDouble("bd", 0.0)); cv.put("exch", o.optDouble("exch", 0.0))
                cv.put("cash", o.optDouble("cash", 0.0)); cv.put("upi", o.optDouble("upi", 0.0)); cv.put("card", o.optDouble("card", 0.0))
                cv.put("finance", o.optString("finance")); cv.put("seller", o.optJSONObject("seller")?.toString() ?: "")
                cv.put("lines", o.optJSONArray("lines")?.toString() ?: "[]")
                w.insertWithOnConflict("bills", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
        if(countRows(ctx, "ledger") == 0) {
            ctx.filesDir.listFiles()?.filter { it.name.startsWith("led_") }?.forEach { f ->
                val o = try { JSONObject(f.readText()) } catch(e: Exception) { null } ?: return@forEach
                val id = if(o.has("id")) o.optLong("id") else (Regex("\\d+").find(f.name.removePrefix("led_"))?.value?.toLongOrNull() ?: 0L)
                val cv = android.content.ContentValues()
                cv.put("id", id); cv.put("date", o.optString("date")); cv.put("party", o.optString("party"))
                cv.put("type", o.optString("type")); cv.put("amt", o.optDouble("amt", 0.0)); cv.put("descr", o.optString("desc"))
                w.insertWithOnConflict("ledger", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
        if(countRows(ctx, "stock") == 0) {
            val arr = try { JSONArray(File(ctx.filesDir, "stock.json").readText()) } catch(e: Exception) { JSONArray() }
            for(i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val cv = android.content.ContentValues()
                cv.put("id", o.optLong("id")); cv.put("model", o.optString("model")); cv.put("imei", o.optString("imei"))
                cv.put("hsn", o.optString("hsn")); cv.put("cost", o.optDouble("cost", 0.0)); cv.put("mrp", o.optDouble("mrp", 0.0))
                cv.put("qty", o.optInt("qty", 0)); cv.put("supplier", o.optString("supplier")); cv.put("addedOn", o.optString("addedOn"))
                w.insertWithOnConflict("stock", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
        if(countRows(ctx, "expenses") == 0) {
            ctx.filesDir.listFiles()?.filter { it.name.startsWith("exp_") }?.forEach { f ->
                val o = try { JSONObject(f.readText()) } catch(e: Exception) { null } ?: return@forEach
                val cv = android.content.ContentValues()
                cv.put("id", o.optLong("id")); cv.put("ms", o.optLong("ms")); cv.put("note", o.optString("note"))
                cv.put("amount", o.optDouble("amount", 0.0))
                w.insertWithOnConflict("expenses", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
        w.setTransactionSuccessful()
        p.edit().putBoolean("dbImported", true).apply()
    } catch(e: Exception) {
        // leave the flag unset so the import is retried on the next launch
    } finally {
        w.endTransaction()
    }
}

fun loadStock(ctx: Context): List<StockEntry> {
    val f = File(ctx.filesDir, "stock.json")
    if(!f.exists()) return emptyList()
    val out = mutableListOf<StockEntry>()
    try {
        val arr = JSONArray(f.readText())
        for(i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(StockEntry(o.optLong("id"), o.optString("model"), o.optString("imei"), o.optString("hsn"),
                o.optDouble("cost", 0.0), o.optDouble("mrp", 0.0), o.optInt("qty", 0), o.optString("supplier"), o.optString("addedOn")))
        }
    } catch(e: Exception) { }
    return out
}
fun saveStock(ctx: Context, list: List<StockEntry>) {
    val arr = JSONArray()
    for(e in list) {
        val o = JSONObject()
        o.put("id", e.id); o.put("model", e.model); o.put("imei", e.imei); o.put("hsn", e.hsn)
        o.put("cost", e.cost); o.put("mrp", e.mrp); o.put("qty", e.qty); o.put("supplier", e.supplier); o.put("addedOn", e.addedOn)
        arr.put(o)
    }
    writeFileAtomic(File(ctx.filesDir, "stock.json"), arr.toString())
}
// Purchase cost of a sold item: matched on IMEI first, then on model name. 0 when unknown.
// Scanning or typing a full IMEI should bring the whole stock line with it.
fun findStockByImei(list: List<StockEntry>, imei: String): StockEntry? {
    val q = imei.trim()
    if(q.length < 10) return null
    return list.firstOrNull { it.imeiList().contains(q) }
}
// Typing a known phone number fills the rest of the buyer from their last bill.
fun findCustomerByPhone(list: List<BuyerInfo>, phone: String): BuyerInfo? {
    val q = phone.trim()
    if(q.length < 10) return null
    return list.firstOrNull { it.phone.trim() == q }
}
fun stockCostFor(list: List<StockEntry>, item: InvItem): Double {
    list.firstOrNull { it.imeiList().contains(item.serial) }?.let { return it.cost }
    list.firstOrNull { it.model.isNotBlank() && item.desc.lowercase().contains(it.model.lowercase()) }?.let { return it.cost }
    return 0.0
}
// Takes sold quantities out of stock. Unknown items are left alone rather than guessed at.
fun deductStock(ctx: Context, items: List<InvItem>) {
    val list = loadStock(ctx).toMutableList()
    var changed = false
    for(item in items) {
        val sold = item.qty.toInt().coerceAtLeast(0)
        if(sold == 0) continue
        val byImei = list.indexOfFirst { it.imeiList().contains(item.serial) }
        if(byImei >= 0) {
            // the exact handset leaves stock: drop its IMEI and one unit
            val e = list[byImei]
            list[byImei] = e.copy(imei = e.imeiList().filter { it != item.serial }.joinToString("\n"),
                                  qty = (e.qty - 1).coerceAtLeast(0))
            changed = true
        } else {
            val idx = list.indexOfFirst { it.model.isNotBlank() && item.desc.lowercase().contains(it.model.lowercase()) }
            if(idx >= 0) {
                list[idx] = list[idx].copy(qty = (list[idx].qty - sold).coerceAtLeast(0))
                changed = true
            }
        }
    }
    if(changed) saveStock(ctx, list)
}
// Clears transactions but keeps every setting, so the shop can start a new period
// without re-typing the outlet details.
fun wipeTransactions(ctx: Context): Int {
    // clear the database tables too, otherwise reports would still see deleted bills
    try {
        val w = db(ctx).writableDatabase
        for(t in listOf("bills", "ledger", "stock", "expenses")) w.delete(t, null, null)
    } catch(e: Exception) { }
    var n = 0
    ctx.filesDir.listFiles()?.forEach { f ->
        if(f.name.startsWith("inv_") || f.name.startsWith("led_") || f.name.startsWith("exp_") || f.name == "stock.json") {
            if(f.delete()) n++
        }
    }
    ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?.listFiles()?.forEach { if(it.delete()) n++ }
    return n
}
fun wipeEverything(ctx: Context): Int {
    val n = wipeTransactions(ctx)
    ctx.getSharedPreferences("penkhata_data", Context.MODE_PRIVATE).edit().clear().apply()
    return n
}
fun writeFileAtomic(f: File, text: String) {
    val tmp = File(f.parentFile, f.name + ".tmp")
    tmp.writeText(text)
    if(!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
}
// Everything needed to stand the app up on a fresh install: all settings, plus the
// invoice and ledger records so the GST tab is not empty after a restore.
// The PIN is deliberately left out of the file.
fun backupJson(ctx: Context): String {
    val prefs = ctx.getSharedPreferences("penkhata_data", Context.MODE_PRIVATE)
    val settings = JSONObject()
    for((k, v) in prefs.all) { if(k != "appPin" && v != null) settings.put(k, v) }
    val invoices = JSONArray(); val ledger = JSONArray(); val expenses = JSONArray()
    ctx.filesDir.listFiles()?.forEach { f ->
        try {
            when {
                f.name.startsWith("inv_") -> invoices.put(JSONObject(f.readText()))
                f.name.startsWith("led_") -> ledger.put(JSONObject(f.readText()))
                f.name.startsWith("exp_") -> expenses.put(JSONObject(f.readText()))
            }
        } catch(e: Exception) { }
    }
    // stock is a single file, kept as-is
    val stockArr = try { JSONArray(File(ctx.filesDir, "stock.json").readText()) } catch(e: Exception) { JSONArray() }
    val root = JSONObject()
    root.put("type", "penkhata-backup")
    root.put("format", 1)
    root.put("savedAt", Date().toString())
    root.put("settings", settings)
    root.put("invoices", invoices)
    root.put("ledger", ledger)
    root.put("expenses", expenses)
    root.put("stock", stockArr)
    return root.toString(2)
}

// Backup is a zip: backup.json plus every generated PDF, so a restore brings the
// bills back into the Files tab, not just the settings.
// Written every time the app is closed, so an accidental exit never costs data.
// Keeps the newest three and lives in the app's Documents folder.
fun autoBackup(ctx: Context): File? {
    return try {
        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "auto-backups").apply { mkdirs() }
        val f = File(dir, "auto-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.ENGLISH).format(Date()) + ".zip")
        FileOutputStream(f).use { backupZip(ctx, it) }
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.delete() }
        f
    } catch(e: Exception) { null }
}
fun backupZip(ctx: Context, out: OutputStream) {
    ZipOutputStream(out).use { zos ->
        zos.putNextEntry(ZipEntry("backup.json"))
        zos.write(backupJson(ctx).toByteArray(Charsets.UTF_8))
        zos.closeEntry()
        ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?.listFiles()
            ?.filter { it.extension.equals("pdf", true) }
            ?.forEach { f ->
                zos.putNextEntry(ZipEntry("pdf/" + f.name))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
    }
}

fun restoreZip(ctx: Context, input: InputStream): String {
    var summary = ""
    var pdfs = 0
    val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
    dir?.mkdirs()
    ZipInputStream(BufferedInputStream(input)).use { zis ->
        var entry = zis.nextEntry
        while(entry != null) {
            val name = entry.name
            if(name == "backup.json") {
                summary = restoreJson(ctx, zis.readBytes().toString(Charsets.UTF_8))
            } else if(name.startsWith("pdf/") && dir != null) {
                val target = File(dir, name.removePrefix("pdf/"))
                if(!target.exists()) { FileOutputStream(target).use { zis.copyTo(it) }; pdfs++ }
            }
            zis.closeEntry()
            entry = zis.nextEntry
        }
    }
    return if(summary.isEmpty()) "That zip has no backup.json inside" else "$summary PDFs restored: $pdfs."
}

fun restoreJson(ctx: Context, text: String): String {
    val root = try { JSONObject(text) } catch(e: Exception) { return "That file is not a PenKhata backup" }
    if(root.optString("type") != "penkhata-backup") return "That file is not a PenKhata backup"
    val prefs = ctx.getSharedPreferences("penkhata_data", Context.MODE_PRIVATE)
    val ed = prefs.edit()
    var settingsCount = 0
    val settings = root.optJSONObject("settings")
    if(settings != null) for(k in settings.keys()) {
        if(k == "appPin") continue
        val raw = settings.opt(k)
        // nextInv is stored as an Int and read back with getInt(), so keep the type
        if(raw is Number) ed.putInt(k, raw.toInt()) else ed.putString(k, raw?.toString() ?: "")
        settingsCount++
    }
    ed.apply()
    var inv = 0; var led = 0
    val stamp = System.currentTimeMillis()
    root.optJSONArray("invoices")?.let { arr ->
        for(i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            writeFileAtomic(File(ctx.filesDir, "inv_${stamp}_$i.json"), o.toString()); inv++
        }
    }
    root.optJSONArray("ledger")?.let { arr ->
        for(i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            writeFileAtomic(File(ctx.filesDir, "led_${stamp}_$i.json"), o.toString()); led++
        }
    }
    var exp = 0
    root.optJSONArray("expenses")?.let { arr ->
        for(i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            writeFileAtomic(File(ctx.filesDir, "exp_${stamp}_$i.json"), o.toString()); exp++
        }
    }
    var stockCount = 0
    root.optJSONArray("stock")?.let { arr ->
        if(arr.length() > 0) { writeFileAtomic(File(ctx.filesDir, "stock.json"), arr.toString()); stockCount = arr.length() }
    }
    return "Restored $settingsCount settings, $inv invoices, $led ledger entries, $exp expenses, $stockCount stock lines. Reopen the app to load them."
}

fun saveInvoiceData(ctx: Context, ms: Long, isQuote: Boolean, invNo: String, dateStr: String, payMode: String, delNote: String,
                    b: BuyerInfo, sel: SellerInfo, billDiscount: Double, exchange: Double, cash: Double, upi: Double, card: Double,
                    finance: String, calc: BillCalc, items: List<InvItem>) {
    val stock = loadStock(ctx)
    var cogs = 0.0
    for(i in items) cogs += stockCostFor(stock, i) * i.qty
    val j=JSONObject()
    j.put("d",Date().toString()); j.put("ms",ms); j.put("quote",isQuote); j.put("inv",invNo)
    j.put("buyer",b.name); j.put("bgst",b.gst)
    j.put("baddr1",b.addr1); j.put("baddr2",b.addr2); j.put("blmk",b.landmark); j.put("bpin",b.pincode)
    j.put("bphone",b.phone); j.put("bstate",b.state)
    j.put("date",dateStr); j.put("payMode",payMode); j.put("delNote",delNote); j.put("finance",finance)
    j.put("bd",billDiscount); j.put("exch",exchange); j.put("cash",cash); j.put("upi",upi); j.put("card",card)
    val sj = JSONObject()
    sj.put("name",sel.name); sj.put("addr",sel.addr); sj.put("phone",sel.phone); sj.put("email",sel.email)
    sj.put("landmark",sel.landmark); sj.put("pincode",sel.pincode); sj.put("gst",sel.gst)
    sj.put("bank",sel.bank); sj.put("ifsc",sel.ifsc); sj.put("juris",sel.juris); sj.put("upi",sel.upi); sj.put("state",sel.state)
    j.put("seller",sj)
    j.put("total",calc.grand); j.put("taxable",calc.taxable); j.put("tax",calc.totalTax)
    j.put("cgst",calc.cgst); j.put("sgst",calc.sgst); j.put("igst",calc.igst); j.put("interState",calc.interState)
    j.put("cogs",cogs); j.put("profit",calc.taxable-cogs)
    j.put("paid",calc.paid); j.put("balance",calc.balance)
    // per-line figures, scaled by the bill discount, so rate-wise summaries add up
    var baseSum = 0.0
    for(i in items) { val g=i.qty*i.rate; val net=g-(g*(i.discount.coerceIn(0.0,100.0)/100.0)); baseSum += net/(1+i.taxRate.coerceIn(0.0,100.0)/100.0) }
    val keep = if(baseSum > 0.0) calc.taxable / baseSum else 1.0
    val lines = JSONArray()
    for(i in items) {
        val g = i.qty*i.rate; val net = g - (g*(i.discount.coerceIn(0.0,100.0)/100.0))
        val tr = i.taxRate.coerceIn(0.0,100.0); val base = net/(1+tr/100.0)
        val o = JSONObject()
        o.put("desc", i.desc); o.put("hsn", i.hsn); o.put("qty", i.qty); o.put("rate", i.rate)
        o.put("serial", i.serial); o.put("unit", i.unit); o.put("discount", i.discount)
        o.put("taxRate", tr); o.put("taxable", base*keep); o.put("tax", (net-base)*keep)
        lines.put(o)
    }
    j.put("lines", lines)
    writeFileAtomic(File(ctx.filesDir,"inv_${ms}.json"), j.toString())
}
fun saveLedgerEntry(ctx: Context, e: LedgerEntry) {
    val ms = System.currentTimeMillis()
    val j = JSONObject()
    j.put("id", ms); j.put("date", e.date); j.put("party", e.party); j.put("type", e.type); j.put("amt", e.amount); j.put("desc", e.desc)
    writeFileAtomic(File(ctx.filesDir, "led_${ms}.json"), j.toString())
}
fun loadLedger(ctx: Context): List<LedgerEntry> {
    val l=mutableListOf<LedgerEntry>()
    ctx.filesDir.listFiles()?.filter{it.name.startsWith("led_")}?.forEach { f ->
        val j = try { JSONObject(f.readText()) } catch(e: Exception) { null }
        if(j != null) {
            // the id is needed to delete the right file; older entries carry it only in the name
            val id = if(j.has("id")) j.optLong("id") else (Regex("\\d+").find(f.name.removePrefix("led_"))?.value?.toLongOrNull() ?: 0L)
            l.add(LedgerEntry(id, j.optString("date"), j.optString("party"), j.optString("type"), j.optDouble("amt",0.0), j.optString("desc")))
        }
    }
    return l
}
fun numberToWords(num: Long): String {
    if(num==0L) return "Zero"
    val u=arrayOf("","One","Two","Three","Four","Five","Six","Seven","Eight","Nine","Ten","Eleven","Twelve","Thirteen","Fourteen","Fifteen","Sixteen","Seventeen","Eighteen","Nineteen")
    val t=arrayOf("","","Twenty","Thirty","Forty","Fifty","Sixty","Seventy","Eighty","Ninety")
    fun rec(n:Long):String{
        if(n<20) return u[n.toInt()]
        if(n<100) return t[(n/10).toInt()] + " " + u[(n%10).toInt()]
        if(n<1000) return u[(n/100).toInt()] + " Hundred " + rec(n%100)
        if(n<100000) return rec(n/1000) + " Thousand " + rec(n%1000)
        if(n<10000000) return rec(n/100000) + " Lakh " + rec(n%100000)
        return rec(n/10000000) + " Crore " + rec(n%10000000)
    }
    return rec(num).trim().replace(Regex("\\s+"), " ")
}
fun amountInWords(amount: Double): String {
    var rupees = amount.toLong()
    var paise = Math.round((amount - rupees) * 100).toInt()
    if(paise >= 100) { rupees += 1; paise = 0 }
    val rw = if(rupees == 1L) "One Rupee" else "${numberToWords(rupees)} Rupees"
    return if(paise == 0) "$rw Only" else "$rw and ${numberToWords(paise.toLong())} Paise Only"
}
// Draws the amount in words inside the free width: shrinks the text until it fits,
// and only wraps to a second line if even the smallest size is too wide.
fun drawAmountInWords(c: Canvas, text: String, x: Float, baseY: Float, p: Paint, width: Float) {
    val startSize = p.textSize
    var size = startSize
    while(size > 7f && p.measureText(text) > width) { size -= 0.5f; p.textSize = size }
    if(p.measureText(text) <= width) {
        c.drawText(text, x, baseY, p)
    } else {
        var y = baseY
        for(l in wrapLines(text, p, width).take(2)) { c.drawText(l, x, y, p); y += p.textSize + 1f }
    }
    p.textSize = startSize
}
// Breaks text into lines that fit 'width': honours explicit newlines, wraps on spaces,
// and hard-splits a single word longer than the cell. Canvas.drawText never breaks on a
// newline character, so every multi-line value must go through this.
fun wrapLines(text: String, p: Paint, width: Float): List<String> {
    val out = mutableListOf<String>()
    if(text.isBlank()) return out
    for(raw in text.replace("\r\n", "\n").split("\n")) {
        val words = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if(words.isEmpty()) continue
        var line = ""
        for(w0 in words) {
            var word = w0
            while(p.measureText(word) > width && word.length > 1) {
                var cut = word.length
                while(cut > 1 && p.measureText(word.substring(0, cut)) > width) cut--
                if(line.isNotEmpty()) { out.add(line); line = "" }
                out.add(word.substring(0, cut)); word = word.substring(cut)
            }
            line = if(line.isEmpty()) word else "$line $word"
            if(p.measureText(line) > width && line.contains(' ')) {
                out.add(line.substringBeforeLast(' ')); line = line.substringAfterLast(' ')
            }
        }
        if(line.isNotEmpty()) out.add(line)
    }
    return out
}
fun qtyText(q: Double): String = if(q == q.toLong().toDouble()) q.toLong().toString() else q.toString()
fun blockHeight(text: String, p: Paint, width: Float, gap: Float = 2f): Float {
    val n = wrapLines(text, p, width).size
    return if(n == 0) 0f else n * (p.textSize + gap)
}
fun drawBlock(c: Canvas, text: String, x: Float, baseY: Float, p: Paint, width: Float, gap: Float = 2f): Float {
    var y = baseY
    for(l in wrapLines(text, p, width)) { c.drawText(l, x, y, p); y += p.textSize + gap }
    return y
}

fun createPdf(ctx: Context, isQuote: Boolean, invNo: String, date: String, payMode: String, delNote: String,
              sName: String, sAddr: String, sPhone: String, sEmail: String, sLandmark: String, sPincode: String,
              sGst: String, sBank: String, sIfsc: String, sJuris: String,
              bName: String, bAddr1: String, bAddr2: String, bLandmark: String, bPincode: String, bPhone: String,
              bGst: String, bState: String, items: List<InvItem>, calc: BillCalc, finance: String, ms: Long, sUpi: String, outDir: File): File {
    val doc = PdfDocument(); var page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
    var c = page.canvas; val p = Paint(); val bp = Paint().apply{style=Paint.Style.STROKE; strokeWidth=1f}
    val m=20f; val w=555f; val h=800f; val midX=m+w/2
    c.drawRect(m, m, m+w, m+h, bp)
    p.isFakeBoldText=true; p.textAlign=Paint.Align.CENTER; p.textSize=14f
    c.drawText(if(isQuote) "QUOTATION" else "TAX INVOICE", midX, m+15, p)
    c.drawLine(m, m+20, m+w, m+20, bp)
    // Seller (top) and buyer (bottom) cells: every value is wrapped inside its own cell and the
    // cell heights are measured from the wrapped text, so nothing crosses a divider or the page edge.
    val r1=m+20; val pad=8f
    val leftX=m+5; val leftW=midX-leftX-8f
    val qX=midX+(m+w-midX)/2; val labX=midX+4f; val labW=qX-labX-4f; val valX=qX+4f; val valW=(m+w)-valX-6f
    val nameP=Paint(p).apply{textSize=12f; isFakeBoldText=true; textAlign=Paint.Align.LEFT}
    val bodyP=Paint(p).apply{textSize=9f; isFakeBoldText=false; textAlign=Paint.Align.LEFT}
    val sDetail=listOf(
        if(sLandmark.isBlank()) "" else "Landmark: $sLandmark",
        if(sPincode.isBlank()) "" else "Pincode: $sPincode",
        if(sPhone.isBlank()) "" else "Ph: $sPhone",
        sEmail
    )
    val sGstLine=if(sGst.isBlank()) "" else "GSTIN: $sGst"
    val bGstLine=if(bGst.isBlank()) "" else "GSTIN: $bGst"
    val bStateLine=if(bState.isBlank()) "" else "State: $bState"
    val bDetail=listOf(bAddr1, bAddr2,
        if(bLandmark.isBlank()) "" else "Landmark: $bLandmark",
        if(bPincode.isBlank()) "" else "Pincode: $bPincode",
        if(bPhone.isBlank()) "" else "Ph: $bPhone")
    fun linesH(lines: List<String>): Float = lines.fold(0f) { acc, t -> acc + blockHeight(t, bodyP, leftW) }
    val sellerH=blockHeight(sName,nameP,leftW)+2f+blockHeight(sAddr,bodyP,leftW)+linesH(sDetail)+blockHeight(sGstLine,bodyP,leftW)+pad
    val buyerH=blockHeight("Buyer: $bName",nameP,leftW)+2f+linesH(bDetail)+blockHeight(bGstLine,bodyP,leftW)+blockHeight(bStateLine,bodyP,leftW)+pad
    val rows=listOf("No" to invNo, "Date" to date, "Del. Note" to delNote, "Pay Mode" to payMode)
    val rowHs=rows.map{ (l,v) -> maxOf(blockHeight(l,bodyP,labW), blockHeight(v,bodyP,valW))+4f }
    val topH=maxOf(sellerH, rowHs[0]+rowHs[1])+pad
    val botH=maxOf(buyerH, rowHs[2]+rowHs[3])+pad
    val midY=r1+topH; val r1End=midY+botH
    c.drawLine(midX,r1,midX,r1End,bp); c.drawLine(m,midY,m+w,midY,bp)
    var ty=r1+pad+nameP.textSize
    ty=drawBlock(c,sName,leftX,ty,nameP,leftW)+2f
    ty=drawBlock(c,sAddr,leftX,ty,bodyP,leftW)
    for(l in sDetail) ty=drawBlock(c,l,leftX,ty,bodyP,leftW)
    drawBlock(c,sGstLine,leftX,ty,bodyP,leftW)
    var buyY=midY+pad+nameP.textSize
    buyY=drawBlock(c,"Buyer: $bName",leftX,buyY,nameP,leftW)+2f
    for(l in bDetail) buyY=drawBlock(c,l,leftX,buyY,bodyP,leftW)
    buyY=drawBlock(c,bGstLine,leftX,buyY,bodyP,leftW)
    drawBlock(c,bStateLine,leftX,buyY,bodyP,leftW)
    val topSplit=r1+topH*(rowHs[0]/(rowHs[0]+rowHs[1])); val botSplit=midY+botH*(rowHs[2]/(rowHs[2]+rowHs[3]))
    c.drawLine(midX,topSplit,m+w,topSplit,bp); c.drawLine(midX,botSplit,m+w,botSplit,bp); c.drawLine(qX,r1,qX,r1End,bp)
    fun rightRow(l:String, v:String, top:Float) { val base=top+4f+bodyP.textSize; drawBlock(c,l,labX,base,bodyP,labW); drawBlock(c,v,valX,base,bodyP,valW) }
    rightRow(rows[0].first,rows[0].second,r1); rightRow(rows[1].first,rows[1].second,topSplit)
    rightRow(rows[2].first,rows[2].second,midY); rightRow(rows[3].first,rows[3].second,botSplit)
    p.textSize=10f; p.textAlign=Paint.Align.LEFT; p.isFakeBoldText=false

    val tTop=r1End; val hH=20f
    val c1=m;val w1=30f;val c2=c1+w1;val w2=200f;val c3=c2+w2;val w3=50f;val c4=c3+w3;val w4=60f;val c5=c4+w4;val w5=70f;val c6=c5+w5;val w6=50f;val c7=c6+w6;val w7=95f
    fun vLine(top:Float,bot:Float){c.drawLine(c2,top,c2,bot,bp);c.drawLine(c3,top,c3,bot,bp);c.drawLine(c4,top,c4,bot,bp);c.drawLine(c5,top,c5,bot,bp);c.drawLine(c6,top,c6,bot,bp);c.drawLine(c7,top,c7,bot,bp)}
    val descW=w2-10f
    val detailPaint=Paint(p).apply{ textSize=8f; textAlign=Paint.Align.LEFT }
    fun rowLines(it: InvItem): Pair<List<String>, List<String>> {
        val descLines = wrapLines(it.desc, p, descW).take(3)
        val discNote = if(it.discount>0.004) "Disc ${fmt(it.discount)}%  -${fmt(it.qty*it.rate*it.discount/100.0)}" else ""
        val detailLines = listOf(if(it.serial.isNotEmpty()) "SR/IMEI: ${it.serial}" else "", discNote)
            .filter{it.isNotBlank()}.flatMap{ wrapLines(it, detailPaint, descW).take(1) }
        return descLines to detailLines
    }
    fun rowHeight(it: InvItem): Float {
        val (d, det) = rowLines(it)
        return Math.max(20f, 6f + d.size*12f + det.size*10f)
    }
    val rowHeights = items.map { rowHeight(it) }
    val totalsRowH = 18f; val totalsBottom = 700f
    var totalsRowCount = 2
    if(calc.itemDiscount + calc.billDiscount > 0.004) totalsRowCount++
    totalsRowCount += if(calc.interState) 1 else 2
    if(Math.abs(calc.roundOff) >= 0.005) totalsRowCount++
    val limitLast = totalsBottom - (totalsRowCount*totalsRowH + 26f) - 6f
    val limitMid = m + h - 40f
    // work out how the rows fall across pages before drawing anything
    val pages = mutableListOf<MutableList<Int>>()
    var idx = 0
    var firstPage = true
    while(idx < items.size) {
        val top = if(firstPage) tTop+hH else m+50f+hH
        var rest = 0f
        for(k in idx until items.size) rest += rowHeights[k]
        val limit = if(top + rest <= limitLast) limitLast else limitMid
        val pg = mutableListOf<Int>()
        var used = 0f
        while(idx < items.size && (pg.isEmpty() || top + used + rowHeights[idx] <= limit)) {
            pg.add(idx); used += rowHeights[idx]; idx++
        }
        pages.add(pg); firstPage = false
    }
    if(pages.isEmpty()) pages.add(mutableListOf())
    // The greedy fill can leave the last page taller than the space above the totals block.
    // Move the overflowing tail down to a fresh page until every page fits.
    while(pages.isNotEmpty()) {
        val li = pages.size - 1
        val topL = if(li == 0) tTop+hH else m+50f+hH
        var used = 0f
        val keep = mutableListOf<Int>(); val move = mutableListOf<Int>()
        for(k in pages[li]) {
            if(topL + used + rowHeights[k] <= limitLast) { keep.add(k); used += rowHeights[k] } else move.add(k)
        }
        if(move.isEmpty()) break
        if(keep.isEmpty()) {
            keep.add(move.removeAt(0))
            if(move.isEmpty()) { pages[li] = keep; break }
        }
        pages[li] = keep
        pages.add(move)
    }
    val totalPages = pages.size
    var pageNo = 1
    var y = tTop+hH
    var tableTop = tTop
    for(pgIdx in pages.indices) {
        if(pgIdx > 0) {
            c.drawLine(m,y,m+w,y,bp)
            p.textSize=8f; p.isFakeBoldText=false; p.textAlign=Paint.Align.RIGHT
            c.drawText("Page $pageNo of $totalPages", m+w-5, m+h+15, p)
            doc.finishPage(page)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNo).create())
            c = page.canvas
            c.drawRect(m, m, m+w, m+h, bp)
            p.isFakeBoldText=true; p.textAlign=Paint.Align.CENTER; p.textSize=14f
            c.drawText(if(isQuote) "QUOTATION" else "TAX INVOICE", midX, m+15, p)
            c.drawLine(m, m+20, m+w, m+20, bp)
            p.isFakeBoldText=false; p.textSize=9f; p.textAlign=Paint.Align.LEFT
            c.drawText("Continued  \u00b7  No: $invNo  \u00b7  $date", m+5, m+36, p)
            tableTop = m+50f
        }
        c.drawLine(m,tableTop,m+w,tableTop,bp); c.drawLine(m,tableTop+hH,m+w,tableTop+hH,bp)
        vLine(tableTop,tableTop+hH)
        p.isFakeBoldText=true; p.textAlign=Paint.Align.CENTER; p.textSize=10f
        c.drawText("SI",c1+w1/2,tableTop+14,p); c.drawText("Desc",c2+w2/2,tableTop+14,p); c.drawText("HSN",c3+w3/2,tableTop+14,p)
        c.drawText("Qty",c4+w4/2,tableTop+14,p); c.drawText("Rate Incl.",c5+w5/2,tableTop+14,p); c.drawText("Per",c6+w6/2,tableTop+14,p); c.drawText("Amt",c7+w7/2,tableTop+14,p)
        p.isFakeBoldText=false
        y = tableTop+hH
        for(i in pages[pgIdx]) {
            val it = items[i]
            val (descLines, detailLines) = rowLines(it)
            c.drawText("${i+1}",c1+w1/2,y+14,p); p.textAlign=Paint.Align.LEFT
            var lineY=y+14f
            for(l in descLines) { c.drawText(l,c2+5,lineY,p); lineY+=12f }
            for(l in detailLines) { c.drawText(l,c2+5,lineY+1f,detailPaint); lineY+=10f }
            p.textAlign=Paint.Align.CENTER; c.drawText(it.hsn,c3+w3/2,y+14,p); c.drawText(qtyText(it.qty),c4+w4/2,y+14,p)
            c.drawText(fmt(it.rate),c5+w5/2,y+14,p); c.drawText(it.unit,c6+w6/2,y+14,p)
            p.textAlign=Paint.Align.RIGHT; c.drawText(fmt(it.rate*it.qty),m+w-5,y+14,p)
            y += rowHeights[i]
        }
    }
    c.drawLine(m,y,m+w,y,bp)
    p.textSize=8f; p.isFakeBoldText=false; p.textAlign=Paint.Align.RIGHT
    c.drawText("Page $pageNo of $totalPages", m+w-5, m+h+15, p)
    // Totals block grows upward from a fixed bottom line, so the extra rows a GST bill
    // needs (discount, IGST, round-off, payment) can never push into the declaration area.
    val tX=c7; val rowH=totalsRowH
    val totalRows=mutableListOf<Pair<String,String>>()
    totalRows.add("Gross Value" to fmt(calc.gross))
    val totalDiscount=calc.itemDiscount+calc.billDiscount
    if(totalDiscount>0.004) totalRows.add("Less Discount" to "-"+fmt(totalDiscount))
    totalRows.add("Taxable Value" to fmt(calc.taxable))
    if(calc.interState) totalRows.add("IGST" to fmt(calc.igst))
    else { totalRows.add("CGST" to fmt(calc.cgst)); totalRows.add("SGST" to fmt(calc.sgst)) }
    if(Math.abs(calc.roundOff)>=0.005) totalRows.add("Round Off" to (if(calc.roundOff>0) "+" else "")+fmt(calc.roundOff))
    val totalsH=totalRows.size*rowH+26f
    val fTop=totalsBottom-totalsH
    c.drawLine(m,fTop,m+w,fTop,bp)
    p.textAlign=Paint.Align.RIGHT; p.textSize=10f; p.isFakeBoldText=false
    var ry=fTop
    for((l,v) in totalRows) {
        c.drawText(l,tX-10,ry+13,p); c.drawText(v,m+w-5,ry+13,p)
        c.drawLine(tX,ry,tX,ry+rowH,bp); c.drawLine(tX,ry+rowH,m+w,ry+rowH,bp); ry+=rowH
    }
    p.isFakeBoldText=true
    c.drawText("Grand Total",tX-10,ry+17,p); c.drawText("\u20b9 "+fmt(calc.grand),m+w-5,ry+17,p)
    val dy=700f
    c.drawLine(tX,fTop,tX,dy,bp)
    p.isFakeBoldText=false; p.textAlign=Paint.Align.LEFT
    val leftLimit=tX-10-(m+5)-4f
    var ly=fTop+16f
    drawAmountInWords(c, "Amount: "+amountInWords(calc.grand), m+5, ly, p, leftLimit)
    p.textSize=9f
    if(calc.paid>0.004) {
        val parts=mutableListOf<String>()
        if(calc.cash>0.004) parts.add("Cash "+fmt(calc.cash))
        if(calc.upi>0.004) parts.add("UPI "+fmt(calc.upi))
        if(calc.card>0.004) parts.add("Card "+fmt(calc.card))
        ly+=14f
        drawAmountInWords(c, "Paid: \u20b9 "+fmt(calc.paid)+(if(parts.isEmpty()) "" else " ("+parts.joinToString(", ")+")"), m+5, ly, p, leftLimit)
    }
    if(Math.abs(calc.balance)>0.004) { ly+=13f; c.drawText("Balance Due: \u20b9 "+fmt(calc.balance),m+5,ly,p) }
    if(calc.exchange>0.004) { ly+=13f; c.drawText("Exchange adjusted: \u20b9 "+fmt(calc.exchange),m+5,ly,p) }
    if(finance.isNotBlank()) { ly+=13f; drawAmountInWords(c, "Finance: "+finance, m+5, ly, p, leftLimit) }
    p.textSize=10f
    if(sBank.isNotEmpty()) {
        val bankY=Math.min(ly+30f, dy-16f)
        if(bankY>ly+12f) {
            c.drawText("Bank Details:",m+5,bankY-9f,p); p.isFakeBoldText=true
            drawAmountInWords(c, "$sBank | $sIfsc", m+5, bankY, p, leftLimit); p.isFakeBoldText=false
        }
    }
    c.drawLine(m,dy,tX,dy,bp); p.isFakeBoldText=false; p.textSize=8f
    c.drawText("Declaration: We declare this invoice shows the actual price of goods.",m+5,dy+12,p)
    c.drawText("Subject to $sJuris Jurisdiction",m+5,dy+22,p); p.isFakeBoldText=true
    c.drawText("GOODS ONCE SOLD CANNOT BE RETURNED",m+5,dy+35,p)
    c.drawLine(c5,dy,m+w,dy,bp)
    p.textSize=10f; p.isFakeBoldText=true; p.textAlign=Paint.Align.CENTER
    val sigX = (c5 + m + w) / 2
    c.drawText("For, $sName",sigX,dy+20,p)
    c.drawText("Authorised Signatory",sigX,m+h-10,p)
    // UPI QR, bottom left, clear of the declaration and the signature block
    if(sUpi.isNotBlank() && calc.grand > 0.004) {
        val qr = qrBitmap(upiLink(sUpi, sName, calc.grand, "Bill " + invNo), 320)
        if(qr != null) {
            val qp = Paint().apply { isFilterBitmap = true }
            c.drawBitmap(qr, null, android.graphics.RectF(m+5, 744f, m+67, 806f), qp)
            p.textSize = 7f; p.textAlign = Paint.Align.LEFT; p.isFakeBoldText = false
            c.drawText("Scan to pay \u20b9" + fmt(calc.grand), m+72, 756f, p)
            c.drawText("UPI: " + sUpi, m+72, 768f, p)
            c.drawText("Open any UPI app", m+72, 780f, p)
            p.textSize = 8f
        }
    }
    p.isFakeBoldText=false; p.textSize=8f; p.textAlign=Paint.Align.CENTER
    c.drawText("Computer generated invoice.",midX,m+h+15,p)
    doc.finishPage(page)
    val safeNo = invNo.replace(Regex("[^A-Za-z0-9._-]"), "-").ifBlank { if(isQuote) "Quote" else "Inv" }
    if(!outDir.exists()) outDir.mkdirs()
    val f = File(outDir, "${safeNo}_${ms}.pdf")
    doc.writeTo(FileOutputStream(f)); doc.close()
    return f
}
// Runs on the main thread; kept out of createPdf so drawing and writing can happen on IO.
// Is the Play Services code scanner usable on this phone?
fun playScannerAvailable(ctx: Context): Boolean =
    GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx) == ConnectionResult.SUCCESS

// Fallback: photograph the barcode with the system camera and decode it with the
// bundled ML Kit model, which needs no Play Services at all.
fun makeScanPhotoFile(ctx: Context): File {
    val dir = File(ctx.cacheDir, "scan").apply { mkdirs() }
    return File(dir, "scan_${System.currentTimeMillis()}.jpg")
}
fun decodeBarcodeFromImage(ctx: Context, f: File, onResult: (String) -> Unit) {
    try {
        val image = InputImage.fromFilePath(ctx, android.net.Uri.fromFile(f))
        BarcodeScanning.getClient().process(image)
            .addOnSuccessListener { barcodes ->
                val v = barcodes.firstOrNull()?.rawValue
                if(v.isNullOrBlank()) Toast.makeText(ctx, "No barcode found in the photo", Toast.LENGTH_LONG).show()
                else onResult(v)
            }
            .addOnFailureListener { e -> Toast.makeText(ctx, "Could not read the photo: " + (e.message ?: "unknown"), Toast.LENGTH_LONG).show() }
    } catch(e: Exception) {
        Toast.makeText(ctx, "Could not read the photo: " + (e.message ?: "unknown"), Toast.LENGTH_LONG).show()
    }
}
fun cameraScanIntent(ctx: Context, target: File): Intent? {
    return try {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", target)
        Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } catch(e: Exception) {
        Toast.makeText(ctx, "Camera scan unavailable: " + (e.message ?: "unknown"), Toast.LENGTH_LONG).show()
        null
    }
}
fun openPdf(ctx: Context, f: File) {
    // getting a shareable uri can fail on its own (provider path not configured, file gone);
    // saying "no PDF viewer" for that sent us chasing the wrong problem once already
    val uri = try {
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", f)
    } catch(e: Exception) {
        Toast.makeText(ctx, "Could not prepare the PDF: " + (e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
        return
    }
    try {
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "View"))
    } catch(e: ActivityNotFoundException) {
        Toast.makeText(ctx, "No PDF viewer is installed on this phone", Toast.LENGTH_LONG).show()
    } catch(e: Exception) {
        Toast.makeText(ctx, "Could not open the PDF: " + (e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
    }
}
