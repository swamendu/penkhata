package com.example.penkhata

import android.app.Activity
import kotlin.math.abs
import android.content.Context
import android.content.ActivityNotFoundException
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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

// True while a file picker we opened is in flight. The app goes to the background for
// it, and locking then would replace the screen holding the pending result.
var pickerInFlight by mutableStateOf(false)

const val APP_VERSION = "7.8.2 · dialog scroll"

data class InvItem(val desc: String, val serial: String, val hsn: String, val qty: Double, val rate: Double, val unit: String, val taxRate: Double, val discount: Double = 0.0,
                   val brand: String = "", val cls: String = "")
val InvItemListSaver: Saver<List<InvItem>, String> = Saver(
    save = { list -> list.joinToString("\u0002") { "${it.desc}\u0001${it.serial}\u0001${it.hsn}\u0001${it.qty}\u0001${it.rate}\u0001${it.unit}\u0001${it.taxRate}\u0001${it.discount}\u0001${it.brand}\u0001${it.cls}" } },
    restore = { s ->
        if(s.isEmpty()) emptyList() else s.split("\u0002").mapNotNull { row ->
            val f = row.split("\u0001")
            if(f.size < 7) null else InvItem(f[0], f[1], f[2], f[3].toDoubleOrNull() ?: 0.0, f[4].toDoubleOrNull() ?: 0.0, f[5], f[6].toDoubleOrNull() ?: 18.0,
                if(f.size > 7) f[7].toDoubleOrNull() ?: 0.0 else 0.0, f.getOrNull(8) ?: "", f.getOrNull(9) ?: "")
        }
    }
)
data class StockEntry(val id: Long, val model: String, val imei: String, val hsn: String,
                      val cost: Double, val mrp: Double, val qty: Int, val supplier: String, val addedOn: String,
                      val brand: String = "", val cls: String = "")
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
    val roundOff: Double, val grand: Double, val exchange: Double, val discountDivisor: Boolean,
    val cash: Double, val upi: Double, val card: Double, val paid: Double, val balance: Double,
    val interState: Boolean
)

// Single place where a bill is added up, so the screen and the PDF can never disagree.
// Rates are tax-inclusive: each line is split into taxable value and tax, item discount is
// applied before the split, and IGST replaces CGST+SGST when the buyer's state differs.
// A discount percentage can mean two different things, and the shop decides which:
//   divisor = true  -> the price is (1 + d%) of the discounted value, so divide  (25,000 / 1.03)
//   divisor = false -> d% off the price, so multiply                              (25,000 x 0.97)
fun discountDivisor(prefs: android.content.SharedPreferences): Boolean = prefs.getBoolean("discDivisor", true)
fun netAfterDiscount(gross: Double, discPct: Double, divisor: Boolean): Double {
    val d = discPct.coerceIn(0.0, 100.0)
    return if(divisor) gross / (1 + d / 100.0) else gross * (1 - d / 100.0)
}
fun discountAmount(gross: Double, discPct: Double, divisor: Boolean): Double =
    gross - netAfterDiscount(gross, discPct, divisor)

fun computeBill(items: List<InvItem>, billDiscount: Double, exchange: Double, cash: Double, upi: Double, card: Double,
                sellerState: String, buyerState: String, divisor: Boolean = true): BillCalc {
    var gross = 0.0; var itemDisc = 0.0; var taxable = 0.0; var tax = 0.0
    for(it in items) {
        val g = it.qty * it.rate
        val net = netAfterDiscount(g, it.discount, divisor)
        val d = g - net
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
        roundOff, grand, exchange, divisor, cash, upi, card, paid, grand - paid, interState)
}

fun fmt(v: Double): String = String.format("%.2f", v)

// Indian financial year label, e.g. "25-26" for bills dated after 1 April 2025.
fun fyLabel(): String {
    val c = Calendar.getInstance()
    val start = if(c.get(Calendar.MONTH) + 1 >= 4) c.get(Calendar.YEAR) else c.get(Calendar.YEAR) - 1
    return String.format("%02d-%02d", start % 100, (start + 1) % 100)
}

// ---------------------------------------------------------------- crash log
// The shop has no PC to run adb on, so the app keeps the last crash itself and
// shows it in Settings. That is the only way a stack trace reaches me.
fun crashLogFile(ctx: Context): File = File(ctx.filesDir, "last_crash.txt")
fun installCrashLogger(ctx: Context) {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        try {
            val text = StringBuilder()
            text.append("When: ").append(SimpleDateFormat("dd-MMM-yyyy HH:mm:ss", Locale.ENGLISH).format(Date())).append("\n")
            text.append("Version: ").append(APP_VERSION).append("\n")
            text.append("Thread: ").append(thread.name).append("\n\n")
            text.append(android.util.Log.getStackTraceString(error))
            crashLogFile(ctx).writeText(text.toString().take(20000))
        } catch(e: Exception) { }
        previous?.uncaughtException(thread, error)
    }
}
fun readCrashLog(ctx: Context): String = try { if(crashLogFile(ctx).exists()) crashLogFile(ctx).readText() else "" } catch(e: Exception) { "" }
fun clearCrashLog(ctx: Context) { try { crashLogFile(ctx).delete() } catch(e: Exception) { } }

// ---------------------------------------------------------------- outlets and people
data class Branch(val code: String, val name: String, val address: String, val pincode: String,
                  val gstin: String, val phone: String, val state: String, val series: String, val nextSerial: Int)
data class Person(val id: Long, val name: String, val role: String, val branch: String, val phone: String, val active: Boolean)

fun loadBranches(ctx: Context): List<Branch> {
    val out = mutableListOf<Branch>()
    try {
        db(ctx).readableDatabase.rawQuery("SELECT code,name,address,pincode,gstin,phone,state,series,nextSerial FROM branches ORDER BY code", null).use { c ->
            while(c.moveToNext()) out.add(Branch(c.getString(0) ?: "", c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "",
                c.getString(4) ?: "", c.getString(5) ?: "", c.getString(6) ?: "", c.getString(7) ?: "", c.getInt(8)))
        }
    } catch(e: Exception) { }
    return out
}
fun saveBranch(ctx: Context, b: Branch) {
    val cv = android.content.ContentValues()
    cv.put("code", b.code); cv.put("name", b.name); cv.put("address", b.address); cv.put("pincode", b.pincode)
    cv.put("gstin", b.gstin); cv.put("phone", b.phone); cv.put("state", b.state); cv.put("series", b.series)
    cv.put("nextSerial", b.nextSerial)
    db(ctx).writableDatabase.insertWithOnConflict("branches", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
}
// First run on an existing install: make a branch out of whatever Settings already holds.
fun ensureBranch(ctx: Context) {
    try {
        if(loadBranches(ctx).isNotEmpty()) return
        val p = ctx.getSharedPreferences("penkhata_data", Context.MODE_PRIVATE)
        val code = (p.getString("sPrefix", "") ?: "").trim().ifEmpty { "MAIN" }
        saveBranch(ctx, Branch(code, p.getString("sName", "") ?: "",
            listOf(p.getString("sAddr1", "") ?: "", p.getString("sAddr2", "") ?: "").filter { it.isNotBlank() }.joinToString(", "),
            p.getString("sPincode", "") ?: "", p.getString("sGst", "") ?: "", p.getString("sPhone", "") ?: "",
            p.getString("sState", "21") ?: "21", (p.getString("sPrefix", "") ?: "INV"), p.getInt("nextInv", 1)))
        if(loadPeople(ctx).isEmpty()) savePerson(ctx, Person(0, "Owner", "OWNER", code, p.getString("sPhone", "") ?: "", true))
    } catch(e: Exception) { }
}
fun loadPeople(ctx: Context): List<Person> {
    val out = mutableListOf<Person>()
    try {
        db(ctx).readableDatabase.rawQuery("SELECT id,name,role,branch,phone,active FROM people ORDER BY role, name", null).use { c ->
            while(c.moveToNext()) out.add(Person(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "", c.getString(4) ?: "", c.getInt(5) != 0))
        }
    } catch(e: Exception) { }
    return out
}
fun savePerson(ctx: Context, p: Person) {
    val cv = android.content.ContentValues()
    if(p.id > 0) cv.put("id", p.id)
    cv.put("name", p.name); cv.put("role", p.role); cv.put("branch", p.branch); cv.put("phone", p.phone)
    cv.put("active", if(p.active) 1 else 0)
    db(ctx).writableDatabase.insertWithOnConflict("people", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
}
fun deletePerson(ctx: Context, id: Long) {
    try { db(ctx).writableDatabase.delete("people", "id=?", arrayOf(id.toString())) } catch(e: Exception) { }
}
fun ownerCount(ctx: Context): Int = loadPeople(ctx).count { it.role == "OWNER" }
fun renamePerson(ctx: Context, id: Long, name: String, role: String, phone: String) {
    try {
        val cv = android.content.ContentValues()
        cv.put("name", name); cv.put("role", role); cv.put("phone", phone)
        db(ctx).writableDatabase.update("people", cv, "id=?", arrayOf(id.toString()))
    } catch(e: Exception) { }
}
// ---------------------------------------------------------------- master data
data class Distributor(val id: Long, val name: String, val gstin: String, val phone: String, val address: String,
                       val creditLimit: Double, val creditDays: Int, val brands: String, val active: Boolean)
data class Product(val id: Long, val brand: String, val category: String, val model: String, val hsn: String,
                   val gstRate: Double, val mrp: Double, val warrantyMonths: Int, val serialised: Boolean)

// ---------------------------------------------------------------- purchases
data class PurchaseLine(val model: String, val brand: String, val category: String, val hsn: String,
                        val qty: Int, val cost: Double, val gstRate: Double, val serials: String)
data class Purchase(val ms: Long, val distributor: String, val invoiceNo: String, val date: String,
                    val taxable: Double, val gst: Double, val total: Double, val creditDays: Int, val note: String,
                    val lines: List<PurchaseLine>)

fun purchaseTaxable(lines: List<PurchaseLine>): Double = lines.sumOf { it.qty * it.cost }
fun purchaseGst(lines: List<PurchaseLine>): Double = lines.sumOf { it.qty * it.cost * it.gstRate / 100.0 }

// One distributor bill in: records the purchase, its lines, any new products, and the
// stock it brings - each serial becoming an individual, traceable unit.
fun savePurchase(ctx: Context, p: Purchase): Boolean {
    if(p.lines.isEmpty()) return false
    return try {
        val w = db(ctx).writableDatabase
        val prefs = ctx.getSharedPreferences("penkhata_data", Context.MODE_PRIVATE)
        w.beginTransaction()
        try {
            val cv = android.content.ContentValues()
            cv.put("ms", p.ms); cv.put("distributor", p.distributor); cv.put("invoiceNo", p.invoiceNo)
            cv.put("date", p.date); cv.put("branch", activeBranchCode(prefs))
            cv.put("taxable", p.taxable); cv.put("gst", p.gst); cv.put("total", p.total)
            cv.put("creditDays", p.creditDays); cv.put("note", p.note)
            w.insertWithOnConflict("purchases", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
            w.delete("purchase_lines", "ms=?", arrayOf(p.ms.toString()))
            for(l in p.lines) {
                val lv = android.content.ContentValues()
                lv.put("ms", p.ms); lv.put("model", l.model); lv.put("brand", l.brand); lv.put("category", l.category)
                lv.put("hsn", l.hsn); lv.put("qty", l.qty); lv.put("cost", l.cost); lv.put("gstRate", l.gstRate)
                lv.put("serials", l.serials)
                w.insert("purchase_lines", null, lv)
            }
            w.setTransactionSuccessful()
        } finally { w.endTransaction() }

        val stock = loadStock(ctx).toMutableList()
        val known = loadProducts(ctx).toMutableList()
        var added = 0
        for(l in p.lines) {
            val serials = l.serials.split("\n", ",", " ").map { it.trim() }.filter { it.isNotBlank() }.distinct()
            val units = if(serials.isNotEmpty()) serials.size else l.qty
            if(units > 0) {
                stock.add(StockEntry(System.currentTimeMillis() + added, l.model, serials.joinToString("\n"), l.hsn,
                    l.cost, 0.0, units, p.distributor, p.date, l.brand, l.category))
                added++
            }
            if(known.none { it.brand.equals(l.brand, true) && it.model.equals(l.model, true) }) {
                saveProduct(ctx, Product(0, l.brand, l.category, l.model, l.hsn, l.gstRate, 0.0, 12, serials.isNotEmpty()))
                known.add(Product(0, l.brand, l.category, l.model, l.hsn, l.gstRate, 0.0, 12, serials.isNotEmpty()))
            }
        }
        if(added > 0) saveStock(ctx, stock)
        true
    } catch(e: Exception) { false }
}
fun loadPurchases(ctx: Context): List<Purchase> {
    val out = mutableListOf<Purchase>()
    try {
        db(ctx).readableDatabase.rawQuery("SELECT ms,distributor,invoiceNo,date,taxable,gst,total,creditDays,note FROM purchases ORDER BY ms DESC", null).use { c ->
            while(c.moveToNext()) out.add(Purchase(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "",
                c.getDouble(4), c.getDouble(5), c.getDouble(6), c.getInt(7), c.getString(8) ?: "", emptyList()))
        }
    } catch(e: Exception) { }
    return out
}

// ---------------------------------------------------------------- stock audit
data class AuditLine(val stockId: Long, val model: String, val brand: String, val bookQty: Int, val countedQty: Int,
                     val cost: Double, val missingSerials: String)
data class StockAudit(val ms: Long, val date: String, val countedBy: String, val note: String,
                      val bookUnits: Int, val countedUnits: Int, val varianceUnits: Int,
                      val bookValue: Double, val varianceValue: Double, val lines: List<AuditLine>)

// A physical count: what the book says, what the shelf says, and the difference.
// The stock is corrected to the counted figure, and the audit keeps the reason.
fun saveAudit(ctx: Context, a: StockAudit): Boolean {
    return try {
        val w = db(ctx).writableDatabase
        val prefs = ctx.getSharedPreferences("penkhata_data", Context.MODE_PRIVATE)
        w.beginTransaction()
        try {
            val cv = android.content.ContentValues()
            cv.put("ms", a.ms); cv.put("date", a.date); cv.put("branch", activeBranchCode(prefs))
            cv.put("countedBy", a.countedBy); cv.put("note", a.note)
            cv.put("bookUnits", a.bookUnits); cv.put("countedUnits", a.countedUnits)
            cv.put("varianceUnits", a.varianceUnits); cv.put("bookValue", a.bookValue); cv.put("varianceValue", a.varianceValue)
            w.insertWithOnConflict("audits", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
            w.delete("audit_lines", "ms=?", arrayOf(a.ms.toString()))
            for(l in a.lines) {
                val lv = android.content.ContentValues()
                lv.put("ms", a.ms); lv.put("stockId", l.stockId); lv.put("model", l.model); lv.put("brand", l.brand)
                lv.put("bookQty", l.bookQty); lv.put("countedQty", l.countedQty); lv.put("cost", l.cost)
                lv.put("missingSerials", l.missingSerials)
                w.insert("audit_lines", null, lv)
            }
            w.setTransactionSuccessful()
        } finally { w.endTransaction() }
        // bring the shelf and the book together
        val stock = loadStock(ctx).toMutableList()
        for(l in a.lines) {
            val idx = stock.indexOfFirst { it.id == l.stockId }
            if(idx >= 0 && stock[idx].qty != l.countedQty) {
                val e = stock[idx]
                val imeis = e.imeiList()
                val kept = if(imeis.size > l.countedQty) imeis.take(l.countedQty) else imeis
                stock[idx] = e.copy(qty = l.countedQty, imei = kept.joinToString("\n"))
            }
        }
        saveStock(ctx, stock)
        true
    } catch(e: Exception) { false }
}
fun loadAudits(ctx: Context): List<StockAudit> {
    val out = mutableListOf<StockAudit>()
    try {
        db(ctx).readableDatabase.rawQuery("SELECT ms,date,countedBy,note,bookUnits,countedUnits,varianceUnits,bookValue,varianceValue FROM audits ORDER BY ms DESC", null).use { c ->
            while(c.moveToNext()) out.add(StockAudit(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "",
                c.getInt(4), c.getInt(5), c.getInt(6), c.getDouble(7), c.getDouble(8), emptyList()))
        }
    } catch(e: Exception) { }
    return out
}
fun loadAuditLines(ctx: Context, ms: Long): List<AuditLine> {
    val out = mutableListOf<AuditLine>()
    try {
        db(ctx).readableDatabase.rawQuery("SELECT stockId,model,brand,bookQty,countedQty,cost,missingSerials FROM audit_lines WHERE ms=?", arrayOf(ms.toString())).use { c ->
            while(c.moveToNext()) out.add(AuditLine(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getInt(3), c.getInt(4), c.getDouble(5), c.getString(6) ?: ""))
        }
    } catch(e: Exception) { }
    return out
}

// ---------------------------------------------------------------- GRN
// A delivery challan is the atom: one paper, one brand, one unique number, one clock.
data class Grn(val id: Long, val no: String, val distributor: String, val brand: String, val date: String,
               val docType: String, val paperTotal: Double, val taxable: Double, val gst: Double, val total: Double,
               val creditDays: Int, val status: String)
data class GrnLine(val sku: String, val model: String, val variant: String, val category: String, val hsn: String,
                   val gstRate: Double, val units: Int, val price: Double, val identType: String)
data class SkuInfo(val sku: String, val brand: String, val category: String, val model: String, val variant: String,
                   val hsn: String, val gstRate: Double, val mrp: Double, val identType: String)
data class StockUnit(val id: Long, val grnId: Long, val sku: String, val serial1: String, val serial2: String,
                     val cost: Double, val dateIn: String, val status: String)

// IMEI only for cellular goods; everything else is a serial; counted goods need neither.
fun identTypeFor(category: String): String = when(category.trim().lowercase()) {
    "mobile" -> "IMEI"
    "watch" -> "IMEI"
    "ac", "air conditioner" -> "SERIAL2"
    "earbuds", "accessories", "spare part", "service" -> "NONE"
    else -> "SERIAL"
}
fun needsIdent(t: String): Boolean = t != "NONE"
fun identLabel(t: String): String = when(t) {
    "IMEI" -> "IMEI (15 digits)"
    "SERIAL2" -> "Indoor serial, then outdoor (optional)"
    "SERIAL" -> "Serial number"
    else -> "No serial needed"
}
// ---------------------------------------------------------------- models and SKUs
// A model is maintained once; its SKUs hang under it. Galaxy F56 5G is the model,
// SM-E556B 8/128 and SM-E556B 8/256 are two SKUs of that same model.
data class Model(val id: Long, val brand: String, val category: String, val name: String)

fun loadModels(ctx: Context): List<Model> {
    val out = mutableListOf<Model>()
    try {
        db(ctx).readableDatabase.rawQuery("SELECT id,brand,category,name FROM models ORDER BY brand, name", null).use { c ->
            while(c.moveToNext()) out.add(Model(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: ""))
        }
    } catch(e: Exception) { }
    return out
}
fun findModel(ctx: Context, brand: String, name: String): Model? = try {
    db(ctx).readableDatabase.rawQuery("SELECT id,brand,category,name FROM models WHERE brand=? AND name=?",
        arrayOf(brand.trim().uppercase(), name.trim())).use { c ->
        if(c.moveToFirst()) Model(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "") else null
    }
} catch(e: Exception) { null }
fun saveModel(ctx: Context, m: Model): Long {
    return try {
        val existing = findModel(ctx, m.brand, m.name)
        if(existing != null) {
            val cv = android.content.ContentValues(); cv.put("category", m.category)
            db(ctx).writableDatabase.update("models", cv, "id=?", arrayOf(existing.id.toString()))
            existing.id
        } else {
            val cv = android.content.ContentValues()
            cv.put("brand", m.brand.trim().uppercase()); cv.put("category", m.category); cv.put("name", m.name.trim())
            db(ctx).writableDatabase.insertWithOnConflict("models", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
            findModel(ctx, m.brand, m.name)?.id ?: -1L
        }
    } catch(e: Exception) { -1L }
}
fun skuModelId(ctx: Context, sku: String): Long = try {
    db(ctx).readableDatabase.rawQuery("SELECT modelId FROM skus WHERE sku=?", arrayOf(sku)).use { if(it.moveToFirst()) it.getLong(0) else 0L }
} catch(e: Exception) { 0L }
fun skusForModel(ctx: Context, modelId: Long): List<SkuInfo> = loadSkus(ctx).filter { skuModelId(ctx, it.sku) == modelId }
fun deleteModel(ctx: Context, id: Long) {
    try {
        val w = db(ctx).writableDatabase
        w.delete("skus", "modelId=?", arrayOf(id.toString()))
        w.delete("models", "id=?", arrayOf(id.toString()))
    } catch(e: Exception) { }
}
// Give every existing SKU a model row, so nothing is orphaned by the new layer.
fun migrateModels(ctx: Context) {
    try {
        val w = db(ctx).writableDatabase
        val rows = mutableListOf<Triple<String, String, String>>()
        db(ctx).readableDatabase.rawQuery("SELECT sku,brand,category,model FROM skus WHERE modelId=0 AND model<>''", null).use { c ->
            while(c.moveToNext()) rows.add(Triple(c.getString(0) ?: "", c.getString(1) ?: "", c.getString(2) ?: ""))
        }
        for((sku, brand, category) in rows) {
            val modelName = try {
                db(ctx).readableDatabase.rawQuery("SELECT model FROM skus WHERE sku=?", arrayOf(sku)).use { if(it.moveToFirst()) it.getString(0) ?: "" else "" }
            } catch(e: Exception) { "" }
            if(modelName.isBlank()) continue
            var m = findModel(ctx, brand, modelName)
            if(m == null) {
                val cv = android.content.ContentValues()
                cv.put("brand", brand.uppercase()); cv.put("category", category); cv.put("name", modelName)
                w.insertWithOnConflict("models", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
                m = findModel(ctx, brand, modelName)
            }
            if(m != null) {
                val cv = android.content.ContentValues(); cv.put("modelId", m.id)
                w.update("skus", cv, "sku=?", arrayOf(sku))
            }
        }
    } catch(e: Exception) { }
}

fun loadSkus(ctx: Context): List<SkuInfo> {
    val out = mutableListOf<SkuInfo>()
    try {
        db(ctx).readableDatabase.rawQuery("SELECT sku,brand,category,model,variant,hsn,gstRate,mrp,identType FROM skus ORDER BY model, variant", null).use { c ->
            while(c.moveToNext()) out.add(SkuInfo(c.getString(0) ?: "", c.getString(1) ?: "", c.getString(2) ?: "",
                c.getString(3) ?: "", c.getString(4) ?: "", c.getString(5) ?: "", c.getDouble(6), c.getDouble(7), c.getString(8) ?: "SERIAL"))
        }
    } catch(e: Exception) { }
    return out
}
fun findSku(ctx: Context, sku: String): SkuInfo? = try {
    db(ctx).readableDatabase.rawQuery("SELECT sku,brand,category,model,variant,hsn,gstRate,mrp,identType FROM skus WHERE sku=?", arrayOf(sku.trim())).use { c ->
        if(c.moveToFirst()) SkuInfo(c.getString(0) ?: "", c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "",
            c.getString(4) ?: "", c.getString(5) ?: "", c.getDouble(6), c.getDouble(7), c.getString(8) ?: "SERIAL") else null
    }
} catch(e: Exception) { null }
fun saveSku(ctx: Context, k: SkuInfo): String? {
    val cv = android.content.ContentValues()
    cv.put("sku", k.sku); cv.put("brand", k.brand); cv.put("category", k.category); cv.put("model", k.model)
    cv.put("variant", k.variant); cv.put("hsn", k.hsn); cv.put("gstRate", k.gstRate); cv.put("mrp", k.mrp)
    cv.put("identType", k.identType)
    return try {
        // every SKU hangs under a model, created the first time that model is seen
        cv.put("modelId", saveModel(ctx, Model(0, k.brand, k.category, k.model)))
        db(ctx).writableDatabase.insertWithOnConflict("skus", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        null
    } catch(e: Exception) { e.message ?: e.javaClass.simpleName }
}
fun loadGrns(ctx: Context): List<Grn> {
    val out = mutableListOf<Grn>()
    try {
        db(ctx).readableDatabase.rawQuery("SELECT id,no,distributor,brand,date,docType,paperTotal,taxable,gst,total,creditDays,status FROM grn ORDER BY createdAt DESC", null).use { c ->
            while(c.moveToNext()) out.add(Grn(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "",
                c.getString(4) ?: "", c.getString(5) ?: "", c.getDouble(6), c.getDouble(7), c.getDouble(8), c.getDouble(9),
                c.getInt(10), c.getString(11) ?: "PENDING"))
        }
    } catch(e: Exception) { }
    return out
}
fun grnExists(ctx: Context, distributor: String, brand: String, no: String): Boolean = try {
    db(ctx).readableDatabase.rawQuery("SELECT 1 FROM grn WHERE distributor=? AND brand=? AND no=?",
        arrayOf(distributor, brand, no.trim())).use { it.moveToFirst() }
} catch(e: Exception) { false }
fun unitsForGrn(ctx: Context, grnId: Long): List<StockUnit> {
    val out = mutableListOf<StockUnit>()
    try {
        db(ctx).readableDatabase.rawQuery("SELECT id,grnId,sku,serial1,serial2,cost,dateIn,status FROM units WHERE grnId=? ORDER BY id", arrayOf(grnId.toString())).use { c ->
            while(c.moveToNext()) out.add(StockUnit(c.getLong(0), c.getLong(1), c.getString(2) ?: "", c.getString(3) ?: "",
                c.getString(4) ?: "", c.getDouble(5), c.getString(6) ?: "", c.getString(7) ?: "PENDING"))
        }
    } catch(e: Exception) { }
    return out
}
fun pendingUnitCount(ctx: Context, grnId: Long): Int = try {
    db(ctx).readableDatabase.rawQuery("SELECT COUNT(*) FROM units WHERE grnId=? AND status='PENDING'", arrayOf(grnId.toString())).use { if(it.moveToFirst()) it.getInt(0) else 0 }
} catch(e: Exception) { 0 }
fun grnLines(ctx: Context, grnId: Long): List<Pair<Long, GrnLine>> {
    val out = mutableListOf<Pair<Long, GrnLine>>()
    try {
        db(ctx).readableDatabase.rawQuery("SELECT id,sku,model,variant,category,hsn,gstRate,units,price,identType FROM grn_lines WHERE grnId=? ORDER BY id", arrayOf(grnId.toString())).use { c ->
            while(c.moveToNext()) out.add(c.getLong(0) to GrnLine(c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "",
                c.getString(4) ?: "", c.getString(5) ?: "", c.getDouble(6), c.getInt(7), c.getDouble(8), c.getString(9) ?: "SERIAL"))
        }
    } catch(e: Exception) { }
    return out
}

// Pass one: record the challan and its lines. Counted goods become stock at once;
// serialised goods wait as PENDING units, which is what keeps them unbillable.
fun saveGrn(ctx: Context, g: Grn, lines: List<GrnLine>): Long {
    if(lines.isEmpty()) return -1
    return try {
        val w = db(ctx).writableDatabase
        val prefs = ctx.getSharedPreferences("penkhata_data", Context.MODE_PRIVATE)
        var grnId = -1L
        w.beginTransaction()
        try {
            val cv = android.content.ContentValues()
            cv.put("no", g.no.trim()); cv.put("distributor", g.distributor); cv.put("brand", g.brand); cv.put("date", g.date)
            cv.put("docType", g.docType); cv.put("paperTotal", g.paperTotal); cv.put("taxable", g.taxable)
            cv.put("gst", g.gst); cv.put("total", g.total); cv.put("creditDays", g.creditDays)
            cv.put("status", if(lines.any { needsIdent(it.identType) }) "PENDING" else "POSTED")
            cv.put("branch", activeBranchCode(prefs)); cv.put("createdAt", System.currentTimeMillis())
            grnId = w.insert("grn", null, cv)
            for(l in lines) {
                val lv = android.content.ContentValues()
                lv.put("grnId", grnId); lv.put("sku", l.sku); lv.put("model", l.model); lv.put("variant", l.variant)
                lv.put("category", l.category); lv.put("hsn", l.hsn); lv.put("gstRate", l.gstRate)
                lv.put("units", l.units); lv.put("price", l.price); lv.put("taxable", l.units * l.price)
                lv.put("identType", l.identType)
                val lineId = w.insert("grn_lines", null, lv)
                val status = if(needsIdent(l.identType)) "PENDING" else "POSTED"
                for(i in 1..l.units) {
                    w.execSQL("INSERT INTO units(grnId,lineId,sku,serial1,serial2,cost,dateIn,status) VALUES(?,?,?,'','',?,?,?)",
                        arrayOf(grnId, lineId, l.sku, l.price, g.date, status))
                }
            }
            w.setTransactionSuccessful()
        } finally { w.endTransaction() }
        // the opening GRN documents stock that is already in hand, so it must not add it again
        if(g.no.trim().uppercase() != "OPENING") postCountedUnits(ctx, grnId)
        grnId
    } catch(e: Exception) { -1 }
}
// Counted lines have no serials, so their units go into stock immediately.
fun postCountedUnits(ctx: Context, grnId: Long) {
    try {
        val g = loadGrns(ctx).firstOrNull { it.id == grnId } ?: return
        val counted = grnLines(ctx, grnId).filter { !needsIdent(it.second.identType) }
        if(counted.isEmpty()) return
        val stock = loadStock(ctx).toMutableList()
        for((_, l) in counted) {
            val existing = stock.indexOfFirst { it.model.equals(l.model, true) && it.brand.equals(g.brand, true) && it.cost == l.price }
            if(existing >= 0) stock[existing] = stock[existing].copy(qty = stock[existing].qty + l.units)
            else stock.add(StockEntry(System.currentTimeMillis() + stock.size, l.model, "", l.hsn, l.price, 0.0,
                l.units, g.distributor, g.date, g.brand, l.category))
        }
        saveStock(ctx, stock)
    } catch(e: Exception) { }
}
// Pass two: record serials for one line, then post those units into stock.
fun saveSerials(ctx: Context, grnId: Long, lineId: Long, serials: List<Pair<String, String>>): Int {
    return try {
        val w = db(ctx).writableDatabase
        val line = grnLines(ctx, grnId).firstOrNull { it.first == lineId }?.second ?: return 0
        val linePending = unitsForGrn(ctx, grnId).filter { it.status == "PENDING" && it.sku == line.sku }
        var done = 0
        for((idx, s) in serials.withIndex()) {
            if(s.first.isBlank()) continue
            val target = linePending.getOrNull(idx) ?: break
            val cv = android.content.ContentValues()
            cv.put("serial1", s.first.trim()); cv.put("serial2", s.second.trim()); cv.put("status", "POSTED")
            w.update("units", cv, "id=?", arrayOf(target.id.toString()))
            done++
        }
        val stillPending = pendingUnitCount(ctx, grnId)
        w.execSQL("UPDATE grn SET status=? WHERE id=?", arrayOf(if(stillPending == 0) "POSTED" else "PENDING", grnId))
        postUnitsToStock(ctx, grnId)
        done
    } catch(e: Exception) { 0 }
}
// Every POSTED unit becomes a stock row of quantity one, so billing finds it by serial.
fun postUnitsToStock(ctx: Context, grnId: Long) {
    try {
        val g = loadGrns(ctx).firstOrNull { it.id == grnId } ?: return
        val lines = grnLines(ctx, grnId).associate { it.second.sku to it.second }
        val stock = loadStock(ctx).toMutableList()
        var changed = false
        db(ctx).readableDatabase.rawQuery(
            "SELECT id,sku,serial1,serial2,cost,dateIn FROM units WHERE grnId=? AND status='POSTED'",
            arrayOf(grnId.toString())).use { c ->
            while(c.moveToNext()) {
                val sku = c.getString(1) ?: ""; val s1 = c.getString(2) ?: ""
                val s2 = c.getString(3) ?: ""; val cost = c.getDouble(4); val dateIn = c.getString(5) ?: ""
                if(s1.isBlank()) continue
                val already = stock.any { it.imeiList().contains(s1) || (s2.isNotBlank() && it.imeiList().contains(s2)) }
                if(already) continue
                val line = lines[sku]
                stock.add(StockEntry(System.currentTimeMillis() + stock.size, line?.model ?: sku,
                    if(s2.isBlank()) s1 else s1 + "\n" + s2, line?.hsn ?: "", cost, 0.0, 1,
                    g.distributor, dateIn, g.brand, line?.category ?: ""))
                changed = true
            }
        }
        if(changed) saveStock(ctx, stock)
    } catch(e: Exception) { }
}
// Opening GRN: whatever stock existed before challans gets a home of its own.
fun openingGrn(ctx: Context) {
    try {
        if(loadGrns(ctx).isNotEmpty()) return
        val stock = loadStock(ctx)
        if(stock.isEmpty()) return
        val taxable = stock.sumOf { it.cost * it.qty }
        val g = Grn(0, "OPENING", "Opening stock", "", SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH).format(Date()),
            "Opening", taxable, taxable, 0.0, taxable, 0, "POSTED")
        val lines = stock.map { e ->
            GrnLine(e.model, e.model, "", if(e.cls.isBlank()) "Mobile" else e.cls, e.hsn, 18.0, e.qty, e.cost, "NONE")
        }
        val id = saveGrn(ctx, g, lines)
        if(id > 0) db(ctx).writableDatabase.execSQL("UPDATE grn SET status='POSTED' WHERE id=?", arrayOf(id))
    } catch(e: Exception) { }
}
fun pendingIdentCount(ctx: Context): Int = try {
    db(ctx).readableDatabase.rawQuery("SELECT COUNT(*) FROM units WHERE status='PENDING'", null).use { if(it.moveToFirst()) it.getInt(0) else 0 }
} catch(e: Exception) { 0 }

// ---------------------------------------------------------------- distributor coverage
// A distributor is a brand's channel. He may hold several brands, and within a brand
// only some categories - Samsung mobiles through one distributor, Samsung appliances
// through another. This is what decides which brand and category a challan may use.
fun loadCoverage(ctx: Context, distributor: String): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    try {
        db(ctx).readableDatabase.rawQuery("SELECT brand,categories FROM dist_brands WHERE distributor=? ORDER BY brand",
            arrayOf(distributor)).use { c -> while(c.moveToNext()) out.add((c.getString(0) ?: "") to (c.getString(1) ?: "")) }
    } catch(e: Exception) { }
    return out
}
fun saveCoverage(ctx: Context, distributor: String, brand: String, categories: List<String>) {
    try {
        val cv = android.content.ContentValues()
        cv.put("distributor", distributor); cv.put("brand", brand)
        cv.put("categories", categories.joinToString(","))
        db(ctx).writableDatabase.insertWithOnConflict("dist_brands", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    } catch(e: Exception) { }
}
fun deleteCoverage(ctx: Context, distributor: String, brand: String) {
    try { db(ctx).writableDatabase.delete("dist_brands", "distributor=? AND brand=?", arrayOf(distributor, brand)) } catch(e: Exception) { }
}
// Brands this distributor may supply. Empty means nothing has been set up yet.
fun brandsForDistributor(ctx: Context, distributor: String): List<String> = loadCoverage(ctx, distributor).map { it.first }
fun categoriesForDistributor(ctx: Context, distributor: String, brand: String): List<String> {
    val row = loadCoverage(ctx, distributor).firstOrNull { it.first.equals(brand, true) } ?: return emptyList()
    return row.second.split(",").map { it.trim() }.filter { it.isNotBlank() }
}

fun loadBrands(ctx: Context): List<String> {
    val out = mutableListOf<String>()
    try { db(ctx).readableDatabase.rawQuery("SELECT name FROM brands ORDER BY name", null).use { c -> while(c.moveToNext()) out.add(c.getString(0) ?: "") } } catch(e: Exception) { }
    return out
}
// Returns true when added, false when it was already there (in any case).
fun addBrand(ctx: Context, name: String): Boolean {
    val n = name.trim().uppercase(); if(n.isBlank()) return false
    return try {
        val exists = loadBrands(ctx).any { it.equals(n, true) }
        if(exists) false else {
            db(ctx).writableDatabase.execSQL("INSERT OR IGNORE INTO brands(name) VALUES(?)", arrayOf(n)); true
        }
    } catch(e: Exception) { false }
}
fun deleteBrand(ctx: Context, name: String) {
    try { db(ctx).writableDatabase.delete("brands", "name=?", arrayOf(name)) } catch(e: Exception) { }
}
fun loadCategories(ctx: Context): List<String> {
    val out = mutableListOf<String>()
    try { db(ctx).readableDatabase.rawQuery("SELECT name FROM categories ORDER BY name", null).use { c -> while(c.moveToNext()) out.add(c.getString(0) ?: "") } } catch(e: Exception) { }
    return out
}
fun addCategory(ctx: Context, name: String): Boolean {
    val n = name.trim(); if(n.isBlank()) return false
    return try {
        val exists = loadCategories(ctx).any { it.equals(n, true) }
        if(exists) false else {
            db(ctx).writableDatabase.execSQL("INSERT OR IGNORE INTO categories(name) VALUES(?)", arrayOf(n)); true
        }
    } catch(e: Exception) { false }
}
fun deleteCategory(ctx: Context, name: String) {
    try { db(ctx).writableDatabase.delete("categories", "name=?", arrayOf(name)) } catch(e: Exception) { }
}
fun loadDistributors(ctx: Context): List<Distributor> {
    val out = mutableListOf<Distributor>()
    try {
        db(ctx).readableDatabase.rawQuery("SELECT id,name,gstin,phone,address,creditLimit,creditDays,brands,active FROM distributors ORDER BY name", null).use { c ->
            while(c.moveToNext()) out.add(Distributor(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "",
                c.getString(4) ?: "", c.getDouble(5), c.getInt(6), c.getString(7) ?: "", c.getInt(8) != 0))
        }
    } catch(e: Exception) { }
    return out
}
fun saveDistributor(ctx: Context, d: Distributor) {
    val cv = android.content.ContentValues()
    if(d.id > 0) cv.put("id", d.id)
    cv.put("name", d.name); cv.put("gstin", d.gstin); cv.put("phone", d.phone); cv.put("address", d.address)
    cv.put("creditLimit", d.creditLimit); cv.put("creditDays", d.creditDays); cv.put("brands", d.brands)
    cv.put("active", if(d.active) 1 else 0)
    db(ctx).writableDatabase.insertWithOnConflict("distributors", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
}
fun deleteDistributor(ctx: Context, id: Long) {
    try { db(ctx).writableDatabase.delete("distributors", "id=?", arrayOf(id.toString())) } catch(e: Exception) { }
}
fun loadProducts(ctx: Context): List<Product> {
    val out = mutableListOf<Product>()
    try {
        db(ctx).readableDatabase.rawQuery("SELECT id,brand,category,model,hsn,gstRate,mrp,warrantyMonths,serialised FROM products ORDER BY brand, model", null).use { c ->
            while(c.moveToNext()) out.add(Product(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "",
                c.getString(4) ?: "", c.getDouble(5), c.getDouble(6), c.getInt(7), c.getInt(8) != 0))
        }
    } catch(e: Exception) { }
    return out
}
fun saveProduct(ctx: Context, p: Product) {
    val cv = android.content.ContentValues()
    if(p.id > 0) cv.put("id", p.id)
    cv.put("brand", p.brand); cv.put("category", p.category); cv.put("model", p.model); cv.put("hsn", p.hsn)
    cv.put("gstRate", p.gstRate); cv.put("mrp", p.mrp); cv.put("warrantyMonths", p.warrantyMonths)
    cv.put("serialised", if(p.serialised) 1 else 0)
    db(ctx).writableDatabase.insertWithOnConflict("products", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
}
// Seed once: the categories this shop sells and the brands it is likely to meet.
fun seedMasterData(ctx: Context) {
    try {
        val cats = listOf("Mobile","AC","TV","Refrigerator","Washing Machine","Inverter","Air Purifier","Water Purifier",
            "Audio","Camera","Watch","Earbuds","Accessories","Spare Part","Service")
        val brands = listOf("SAMSUNG","LG","SONY","PANASONIC","WHIRLPOOL","GODREJ","HAIER","BOSCH","IFB","VOLTAS","BLUE STAR",
            "HITACHI","DAIKIN","LLOYD","MIDEA","ONIDA","SHARP","PHILIPS","BAJAJ","HAVELLS","CROMPTON","ORIENT","V-GUARD",
            "LUMINOUS","MICROTEK","EXIDE","AMARON","XIAOMI","REALME","ONEPLUS","OPPO","VIVO","IQOO","MOTOROLA","NOKIA",
            "NOTHING","TECNO","INFINIX","ITEL","LAVA","APPLE","GOOGLE","ASUS","HP","DELL","LENOVO","ACER","CANON","NIKON",
            "GOPRO","JBL","BOAT","MARSHALL","SENNHEISER","NOISE","FIRE-BOLTT","AMAZFIT","GARMIN","TITAN","FASTRACK","CASIO",
            "SANDISK","KINGSTON","TRANSCEND","PORTRONICS","AMBRANE","KENT","AQUAGUARD","PUREIT","LIVPURE","ATOMBERG","USHA",
            "SYMPHONY","KENSTAR")
        db(ctx).writableDatabase.beginTransaction()
        try {
            for(c in cats) db(ctx).writableDatabase.execSQL("INSERT OR IGNORE INTO categories(name) VALUES(?)", arrayOf(c))
            for(b in brands) db(ctx).writableDatabase.execSQL("INSERT OR IGNORE INTO brands(name) VALUES(?)", arrayOf(b))
            db(ctx).writableDatabase.setTransactionSuccessful()
        } finally { db(ctx).writableDatabase.endTransaction() }
    } catch(e: Exception) { }
}

fun activeBranchCode(prefs: android.content.SharedPreferences): String = prefs.getString("activeBranch", "") ?: ""
fun activePerson(prefs: android.content.SharedPreferences): String = prefs.getString("activePerson", "Owner") ?: "Owner"
fun activeRole(prefs: android.content.SharedPreferences): String = prefs.getString("activeRole", "OWNER") ?: "OWNER"

// Numbers are per outlet and series, so two shops can both have 00001 without colliding.
fun nextInvoiceNo(ctx: Context, prefs: android.content.SharedPreferences): String {
    val branches = loadBranches(ctx)
    val b = branches.firstOrNull { it.code == activeBranchCode(prefs) } ?: branches.firstOrNull()
    if(b == null) {
        val prefix = (prefs.getString("sPrefix", "") ?: "").trim().ifEmpty { "INV" }
        return "$prefix/${fyLabel()}/" + String.format("%03d", prefs.getInt("nextInv", 1))
    }
    val n = String.format("%05d", b.nextSerial)
    return if(b.series.isBlank()) "${b.code}/${fyLabel()}/$n" else "${b.code}/${b.series}/$n"
}
fun bumpInvoiceNo(ctx: Context, prefs: android.content.SharedPreferences) {
    try {
        val branches = loadBranches(ctx)
        val b = branches.firstOrNull { it.code == activeBranchCode(prefs) } ?: branches.firstOrNull() ?: return
        saveBranch(ctx, b.copy(nextSerial = b.nextSerial + 1))
    } catch(e: Exception) { }
}

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashLogger(this)
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF2E7D32))) { MainAppScreen() } }
    }
}

// ---------------------------------------------------------------- app lock
// The PIN is never stored as typed: only a salted SHA-256 hash is kept.
fun sha256Hex(text: String): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    return md.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
fun newSalt(): String {
    val b = ByteArray(16); java.security.SecureRandom().nextBytes(b)
    return b.joinToString("") { "%02x".format(it) }
}
fun pinMatches(prefs: android.content.SharedPreferences, typed: String): Boolean {
    val hash = prefs.getString("pinHash", "") ?: ""
    val salt = prefs.getString("pinSalt", "") ?: ""
    if(hash.isBlank()) return false
    return sha256Hex(salt + typed) == hash
}
fun storePin(prefs: android.content.SharedPreferences, pin: String) {
    if(pin.isBlank()) {
        prefs.edit().remove("pinHash").remove("pinSalt").remove("appPin").apply()
    } else {
        val salt = newSalt()
        prefs.edit().putString("pinSalt", salt).putString("pinHash", sha256Hex(salt + pin)).remove("appPin").apply()
    }
}
// An old build stored the PIN in the clear; hash it in place and drop the original.
fun migratePlainPin(prefs: android.content.SharedPreferences) {
    val plain = prefs.getString("appPin", "") ?: ""
    if(plain.isNotBlank() && (prefs.getString("pinHash", "") ?: "").isBlank()) storePin(prefs, plain)
    else if(plain.isNotBlank()) prefs.edit().remove("appPin").apply()
}
fun hasPin(prefs: android.content.SharedPreferences): Boolean = !(prefs.getString("pinHash", "") ?: "").isBlank()
fun biometricReady(ctx: Context): Boolean =
    BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
fun askBiometric(activity: FragmentActivity, onUnlocked: () -> Unit, onUsePin: () -> Unit) {
    try {
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onUnlocked() }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { onUsePin() }
            })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock PenKhata")
            .setSubtitle("Use your fingerprint")
            .setNegativeButtonText("Use PIN")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build())
    } catch(e: Exception) { onUsePin() }
}

@Composable
fun MainAppScreen() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("penkhata_data", Context.MODE_PRIVATE) }
    remember { migratePlainPin(prefs) }
    var hasPinState by remember { mutableStateOf(hasPin(prefs)) }
    var isLocked by remember { mutableStateOf(hasPin(prefs)) }
    var menuOpen by remember { mutableStateOf(false) }
    // lock again when the app goes to the background, not just on a cold start
    // FLAG_SECURE stops screenshots and screen recording. The owner may capture; a
    // manager, stock keeper or rep may not, so the shop's data cannot walk out as an image.
    val windowActivity = ctx as? Activity
    LaunchedEffect(activeRole(prefs), prefs.getBoolean("blockShots", true)) {
        try {
            if(windowActivity != null && prefs.getBoolean("blockShots", true) && activeRole(prefs) != "OWNER")
                windowActivity.window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
            else windowActivity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        } catch(e: Exception) { }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, hasPinState) {
        val obs = LifecycleEventObserver { _, event ->
            if(event == Lifecycle.Event.ON_STOP && hasPinState && !pickerInFlight) isLocked = true
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    var currentScreen by remember { mutableStateOf("dashboard") }
    // bumped after a restore or a reset: every screen rebuilds and re-reads its data
    var dataVersion by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { importJsonIfNeeded(ctx); ensureBranch(ctx); seedMasterData(ctx); migrateCustomers(ctx); migrateModels(ctx); openingGrn(ctx) }
        dataVersion++
    }

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
    if (isLocked) LoginScreen(prefs) { isLocked = false }
    else Scaffold(
    ) { p -> Box(Modifier.padding(p)) {
        Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("\u2630", fontSize = 22.sp, modifier = Modifier.clickable { menuOpen = true }.padding(8.dp))
            Text(activeBranchCode(prefs).ifBlank { "PenKhata" }, fontSize = 11.sp, color = Color.Gray, modifier = Modifier.weight(1f))
            Text(activeRole(prefs), fontSize = 9.sp, color = Color.Gray)
        }
        Box(Modifier.weight(1f)) { key(dataVersion) { when(currentScreen) {
        "dashboard" -> InvoiceScreen(false)
        "ledger" -> LedgerScreen()
        "stock" -> StockScreen()
        "reports" -> GSTReportScreen()
        "history" -> FileHistoryScreen()
        else -> SettingsScreen(hasPin(prefs), onDataChanged = { dataVersion++ }) { new -> storePin(prefs, new); hasPinState = hasPin(prefs) }
            }}}
        }
        if(menuOpen) Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable { menuOpen = false }) {
            Card(Modifier.fillMaxWidth(0.8f).fillMaxHeight().background(Color.White)) { Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text("MENU", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("\u2715", fontSize = 18.sp, modifier = Modifier.clickable { menuOpen = false }.padding(6.dp))
                }
                Divider(Modifier.padding(vertical = 8.dp))
                listOf(
                    "dashboard" to "Entry / Billing",
                    "ledger" to "Ledger",
                    "stock" to "Stock",
                    "reports" to "Reports",
                    "history" to "Files",
                    "settings" to "Settings"
                ).forEach { (id, label) ->
                    Text(label, fontSize = 14.sp, modifier = Modifier.fillMaxWidth()
                        .clickable { currentScreen = id; menuOpen = false }
                        .padding(vertical = 10.dp))
                }
                Divider(Modifier.padding(vertical = 8.dp))
                Text("Acting as " + activePerson(prefs) + " (" + activeRole(prefs) + ")", fontSize = 10.sp, color = Color.Gray)
            }}
        }
    }}
}

@Composable
fun LoginScreen(prefs: android.content.SharedPreferences, unlock: () -> Unit) {
    val ctx = LocalContext.current
    val focusManager = LocalFocusManager.current
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("LOCKED", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(value=input, onValueChange={input=it; error=""}, visualTransformation=PasswordVisualTransformation(),
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword, imeAction=ImeAction.Done),
            keyboardActions=KeyboardActions(onDone={ if(pinMatches(prefs, input)) unlock() else error = "Wrong PIN" }),
            label={Text("PIN")}, modifier=Modifier.fillMaxWidth().padding(24.dp))
        if(error.isNotBlank()) Text(error, color = Color.Red, fontSize = 12.sp)
        Button(onClick={ if(pinMatches(prefs, input)) unlock() else error = "Wrong PIN" }, modifier=Modifier.padding(8.dp)) { Text("UNLOCK") }
        val activity = ctx as? FragmentActivity
        if(activity != null && biometricReady(ctx)) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { askBiometric(activity, { unlock() }, { }) }) { Text("USE FINGERPRINT") }
        }
    }
}

@Composable
fun InvoiceScreen(isQuote: Boolean) {
    val focusManager = LocalFocusManager.current
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("penkhata_data", android.content.Context.MODE_PRIVATE) }
    val sellerState = prefs.getString("sState", "21") ?: "21"
    var invNo by rememberSaveable { mutableStateOf(nextInvoiceNo(ctx, prefs)) }
    var date by rememberSaveable { mutableStateOf(SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH).format(Date())) }
    var payMode by rememberSaveable { mutableStateOf("") }; var delNote by rememberSaveable { mutableStateOf("") }
    var bName by rememberSaveable { mutableStateOf("") }
    var bAddr1 by rememberSaveable { mutableStateOf("") }; var bAddr2 by rememberSaveable { mutableStateOf("") }
    var bLandmark by rememberSaveable { mutableStateOf("") }; var bPincode by rememberSaveable { mutableStateOf("") }
    var bPhone by rememberSaveable { mutableStateOf("") }
    var nameLocked by rememberSaveable { mutableStateOf(false) }
    var ownerUnlock by remember { mutableStateOf(false) }
    var unlockPin by remember { mutableStateOf("") }
    var custQuery by rememberSaveable { mutableStateOf("") }
    var custResults by remember { mutableStateOf(listOf<BuyerInfo>()) }
    if(ownerUnlock) AlertDialog(
        onDismissRequest = { ownerUnlock = false; unlockPin = "" },
        title = { Text("Bill under a different name?") },
        text = { Column {
            Text("The customer record keeps its original name. This changes only this bill, and the owner PIN is required.", fontSize = 11.sp)
            OutlinedTextField(unlockPin, {unlockPin=it}, label={Text("Owner PIN")}, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword), modifier=Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(onClick = {
            if(pinMatches(prefs, unlockPin)) { nameLocked = false; ownerUnlock = false; unlockPin = ""; Toast.makeText(ctx, "Name editable for this bill only", Toast.LENGTH_SHORT).show() }
            else Toast.makeText(ctx, "Wrong PIN", Toast.LENGTH_SHORT).show()
        }) { Text("UNLOCK") } },
        dismissButton = { TextButton(onClick = { ownerUnlock = false; unlockPin = "" }) { Text("CANCEL") } }
    )
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
    var iBrand by rememberSaveable { mutableStateOf("") }
    var iCls by rememberSaveable { mutableStateOf("") }
    fun applyStockByImei(imeiNo: String) {
        val e = findStockByImei(stockList, imeiNo) ?: return
        iBrand = e.brand; iCls = e.cls
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
        invNo = nextInvoiceNo(ctx, prefs)
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
        cash.toDoubleOrNull() ?: 0.0, upi.toDoubleOrNull() ?: 0.0, card.toDoubleOrNull() ?: 0.0, sellerState, bState, discountDivisor(prefs))

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
                Text("Customers (" + customerCount(ctx) + ")", fontWeight = FontWeight.Bold)
                TextButton(onClick = { showCustomers = !showCustomers }) { Text(if(showCustomers) "HIDE" else "SEARCH") }
            }
            if(showCustomers) {
                OutlinedTextField(custQuery, { custQuery = it; custResults = searchCustomers(ctx, it, 20) },
                    label={Text("Phone or name")}, modifier=Modifier.fillMaxWidth(),
                    keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),
                    keyboardActions=KeyboardActions(onSearch={ custResults = searchCustomers(ctx, custQuery, 20) }))
                Text(if(custQuery.length < 2) "Type at least 2 characters - searches the whole customer list" else "${custResults.size} shown", fontSize = 9.sp, color = Color.Gray)
            }
            if(showCustomers) custResults.forEach { c ->
                Column(Modifier.fillMaxWidth().clickable {
                    bName = c.name; bAddr1 = c.addr1; bAddr2 = c.addr2; bLandmark = c.landmark
                    bPincode = c.pincode; bPhone = c.phone; bGst = c.gst; bState = c.state
                    nameLocked = true
                    showCustomers = false
                    Toast.makeText(ctx, "Filled from the customer record", Toast.LENGTH_SHORT).show()
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
            if(nameLocked) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text("Name locked to the registered owner of this number", fontSize = 9.sp, color = Color(0xFF2E7D32))
                    TextButton(onClick = { ownerUnlock = true }) { Text("BILL ANOTHER NAME", fontSize = 9.sp) }
                }
            }
            OutlinedTextField(bName, {if(!nameLocked) bName=it}, readOnly = nameLocked, label={Text("Name")}, modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
            OutlinedTextField(bAddr1, {bAddr1=it}, label={Text("Address Line 1")}, modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
            OutlinedTextField(bAddr2, {bAddr2=it}, label={Text("Address Line 2")}, modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
            OutlinedTextField(bLandmark, {bLandmark=it}, label={Text("Landmark")}, modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
            OutlinedTextField(bPincode, {bPincode=it}, label={Text("Pincode")}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number, imeAction=ImeAction.Next), modifier=Modifier.fillMaxWidth(), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
            OutlinedTextField(bPhone, {
                bPhone = it
                applyCustomerByPhone(it)
                if(it.trim().length >= 10) {
                    val known = findCustomerByPhone(ctx, it)
                    if(known != null) {
                        bName = known.name; bAddr1 = known.addr1; bAddr2 = known.addr2; bLandmark = known.landmark
                        bPincode = known.pincode; bGst = known.gst; bState = known.state
                        nameLocked = true
                        Toast.makeText(ctx, "Number registered to " + known.name, Toast.LENGTH_SHORT).show()
                    } else {
                        nameLocked = false
                    }
                }
            }, label={Text("Phone")}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Phone, imeAction=ImeAction.Next), modifier=Modifier.fillMaxWidth(), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
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
                    iBrand = entry.brand; iCls = entry.cls
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
                        (iTax.toDoubleOrNull() ?: 18.0).coerceIn(0.0, 100.0), (iDisc.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 100.0),
                        iBrand, iCls)
                    iDesc=""; iSerial=""; iQty="1"; iRate=""; iDisc="0"; iBrand=""; iCls=""
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
                            bumpInvoiceNo(ctx, prefs); clearBill()
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
                        val age = partyAgeDays(entries, invoices, acc.party, System.currentTimeMillis())
                        val ageText = when {
                            acc.net <= 0.004 -> ""
                            age >= 90 -> "  \u00b7  oldest ${age}d"
                            age >= 60 -> "  \u00b7  oldest ${age}d"
                            age >= 30 -> "  \u00b7  oldest ${age}d"
                            age > 0 -> "  \u00b7  ${age}d"
                            else -> "  \u00b7  today"
                        }
                        Text("Bills due \u20b9${fmt(acc.billDue)}  \u00b7  khata \u20b9${fmt(acc.manual)}" + ageText,
                             fontSize = 10.sp,
                             color = if(age >= 60 && acc.net > 0.004) Color.Red else Color.Unspecified)
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
                        withContext(Dispatchers.IO) { deleteLedgerEntry(ctx, it.id) }
                        reload++
                    }
                }) { Icon(Icons.Default.Delete, "Del", tint = Color.Gray) }
            }
            Divider()
        }
        Spacer(Modifier.height(50.dp))
    }
}

@Composable
fun StockAuditScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("penkhata_data", Context.MODE_PRIVATE) }
    var reload by remember { mutableStateOf(0) }
    var note by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    // counted figures keyed by stock row id, defaulting to the book quantity
    var counted by remember { mutableStateOf(mapOf<Long, String>()) }
    val stock by produceState(initialValue = emptyList<StockEntry>(), reload) { value = withContext(Dispatchers.IO) { loadStock(ctx) } }
    val audits by produceState(initialValue = emptyList<StockAudit>(), reload) { value = withContext(Dispatchers.IO) { loadAudits(ctx) } }

    fun countedFor(e: StockEntry): Int = counted[e.id]?.toIntOrNull() ?: e.qty

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("STOCK AUDIT", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = { onDone() }) { Text("BACK") }
        }
        Text("Count what is actually on the shelf. Anything different is recorded against " + activePerson(prefs) + ".", fontSize = 10.sp, color = Color.Gray)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(note, {note=it}, label={Text("Note (e.g. month end count)")}, modifier=Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))

        if(stock.isEmpty()) Text("No stock to count.", fontSize = 11.sp)
        stock.sortedBy { it.model.lowercase() }.forEach { e ->
            Card(Modifier.padding(vertical = 3.dp)) { Column(Modifier.padding(10.dp)) {
                Text(e.model, fontWeight = FontWeight.Bold, maxLines = 1, fontSize = 12.sp)
                Text((if(e.brand.isBlank()) "" else e.brand + " \u00b7 ") + "book " + e.qty + "  \u00b7  cost \u20b9" + fmt(e.cost), fontSize = 10.sp, color = Color.Gray)
                if(e.imeiList().isNotEmpty()) Text(e.imeiList().joinToString(", "), fontSize = 9.sp, maxLines = 2)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        counted[e.id] ?: e.qty.toString(),
                        { v -> counted = counted + (e.id to v) },
                        label = { Text("Counted") },
                        modifier = Modifier.width(120.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)
                    )
                    Spacer(Modifier.width(10.dp))
                    val diff = countedFor(e) - e.qty
                    Text(if(diff == 0) "matches" else (if(diff > 0) "+" else "") + diff + " units", fontSize = 11.sp,
                        color = if(diff == 0) Color(0xFF2E7D32) else Color.Red)
                }
            }}
        }

        Spacer(Modifier.height(10.dp))
        val bookUnits = stock.sumOf { it.qty }
        val countedUnits = stock.sumOf { countedFor(it) }
        val varianceValue = stock.sumOf { (countedFor(it) - it.qty) * it.cost }
        Card(Modifier.padding(vertical = 5.dp)) { Column(Modifier.padding(10.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Book units", fontSize = 11.sp); Text("$bookUnits", fontSize = 11.sp) }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Counted units", fontSize = 11.sp); Text("$countedUnits", fontSize = 11.sp) }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Text("Variance", fontWeight = FontWeight.Bold)
                Text("${countedUnits - bookUnits} units  \u00b7  \u20b9${fmt(varianceValue)}",
                    fontWeight = FontWeight.Bold, color = if(countedUnits == bookUnits) Color(0xFF2E7D32) else Color.Red)
            }
            Button(onClick = {
                saving = true
                scope.launch {
                    val a = StockAudit(System.currentTimeMillis(), SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH).format(Date()),
                        activePerson(prefs), note.trim(), bookUnits, countedUnits, countedUnits - bookUnits,
                        stock.sumOf { it.cost * it.qty }, varianceValue,
                        stock.map { e ->
                            val c = countedFor(e)
                            val missing = if(e.imeiList().size > c) e.imeiList().drop(c).joinToString(", ") else ""
                            AuditLine(e.id, e.model, e.brand, e.qty, c, e.cost, missing)
                        })
                    val ok = withContext(Dispatchers.IO) { saveAudit(ctx, a) }
                    saving = false
                    if(ok) {
                        Toast.makeText(ctx, "Audit saved. Stock corrected to the counted figures.", Toast.LENGTH_LONG).show()
                        counted = mapOf(); note = ""; reload++
                    } else {
                        Toast.makeText(ctx, "Could not save the audit", Toast.LENGTH_LONG).show()
                    }
                }
            }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text(if(saving) "SAVING..." else "SAVE AUDIT") }
        }}

        if(audits.isNotEmpty()) Card(Modifier.padding(vertical = 5.dp)) { Column(Modifier.padding(10.dp)) {
            Text("Previous audits", fontWeight = FontWeight.Bold)
            audits.take(10).forEach { a ->
                Column(Modifier.padding(vertical = 3.dp)) {
                    Text(a.date + "  \u00b7  " + a.countedBy, fontSize = 11.sp)
                    Text("book " + a.bookUnits + " \u00b7 counted " + a.countedUnits + " \u00b7 variance " + a.varianceUnits +
                         "  (\u20b9" + fmt(a.varianceValue) + ")" + (if(a.note.isBlank()) "" else "  \u00b7  " + a.note),
                        fontSize = 9.sp, color = if(a.varianceUnits == 0) Color.Gray else Color.Red)
                }
                Divider()
            }
        }}
    }
}

@Composable
fun StockMasterScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableStateOf(0) }
    var tab by rememberSaveable { mutableStateOf("models") }

    // search-first: nothing is listed until you ask, because there may be thousands
    var q by rememberSaveable { mutableStateOf("") }
    var fBrand by rememberSaveable { mutableStateOf("") }
    var fCat by rememberSaveable { mutableStateOf("") }
    var fOnlyNoSku by rememberSaveable { mutableStateOf(false) }

    var brandQ by rememberSaveable { mutableStateOf("") }
    var catQ by rememberSaveable { mutableStateOf("") }
    var distQ by rememberSaveable { mutableStateOf("") }

    var newBrand by rememberSaveable { mutableStateOf("") }
    var newCat by rememberSaveable { mutableStateOf("") }
    var newDist by rememberSaveable { mutableStateOf("") }
    var newDistLimit by rememberSaveable { mutableStateOf("") }
    var newDistDays by rememberSaveable { mutableStateOf("30") }

    var newModelBrand by rememberSaveable { mutableStateOf("") }
    var newModelName by rememberSaveable { mutableStateOf("") }
    var newModelCat by rememberSaveable { mutableStateOf("Mobile") }
    var skuForModel by remember { mutableStateOf<Model?>(null) }
    var skuCode by remember { mutableStateOf("") }
    var coverFor by remember { mutableStateOf("") }
    var coverBrand by remember { mutableStateOf("") }
    var coverCats by remember { mutableStateOf(setOf<String>()) }

    val brands by produceState(initialValue = emptyList<String>(), reload) { value = withContext(Dispatchers.IO) { loadBrands(ctx) } }
    val cats by produceState(initialValue = emptyList<String>(), reload) { value = withContext(Dispatchers.IO) { loadCategories(ctx) } }
    val dists by produceState(initialValue = emptyList<Distributor>(), reload) { value = withContext(Dispatchers.IO) { loadDistributors(ctx) } }
    val models by produceState(initialValue = emptyList<Model>(), reload) { value = withContext(Dispatchers.IO) { loadModels(ctx) } }
    val skus by produceState(initialValue = emptyList<SkuInfo>(), reload) { value = withContext(Dispatchers.IO) { loadSkus(ctx) } }

    fun skusOf(m: Model): List<SkuInfo> = skus.filter { it.model.equals(m.name, true) && it.brand.equals(m.brand, true) }
    fun matches(m: Model): Boolean {
        val skuText = skusOf(m).joinToString(" ") { it.sku }
        val hay = (m.brand + " " + m.name + " " + m.category + " " + skuText).lowercase()
        if(q.isNotBlank() && !q.trim().lowercase().split(" ").all { hay.contains(it) }) return false
        if(fBrand.isNotBlank() && !m.brand.equals(fBrand, true)) return false
        if(fCat.isNotBlank() && !m.category.equals(fCat, true)) return false
        if(fOnlyNoSku && skusOf(m).isNotEmpty()) return false
        return true
    }

    if(skuForModel != null) AlertDialog(
        onDismissRequest = { skuForModel = null },
        title = { Text("Add a SKU") },
        text = { Column {
            Text("Brand: " + (skuForModel?.brand ?: "") + "   Model: " + (skuForModel?.name ?: ""), fontSize = 10.sp, color = Color.Gray)
            OutlinedTextField(skuCode, {skuCode=it}, label={Text("SKU from the box, e.g. E556B 8/128")}, modifier=Modifier.fillMaxWidth())
            Text("The model already says 8/128, so only the SKU is needed here.", fontSize = 9.sp, color = Color.Gray)
        } },
        confirmButton = { TextButton(onClick = {
            val m = skuForModel
            if(m != null && skuCode.isNotBlank()) {
                scope.launch {
                    val err = withContext(Dispatchers.IO) {
                        saveSku(ctx, SkuInfo(skuCode.trim(), m.brand, m.category, m.name, "", "", 18.0, 0.0, identTypeFor(m.category)))
                    }
                    if(err == null) {
                        skuForModel = null; skuCode = ""; reload++
                        Toast.makeText(ctx, "SKU " + skuCode.trim() + " saved under " + m.name, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(ctx, "Could not save the SKU: " + err, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }) { Text("SAVE SKU") } },
        dismissButton = { TextButton(onClick = { skuForModel = null }) { Text("CANCEL") } }
    )

    if(coverFor.isNotBlank()) AlertDialog(
        onDismissRequest = { coverFor = "" },
        title = { Text("What does " + coverFor + " supply?") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            Text("Pick a brand he is authorised for, then the categories within it.", fontSize = 10.sp, color = Color.Gray)
            OutlinedTextField(coverBrand, {coverBrand=it.uppercase()}, label={Text("Brand")}, modifier=Modifier.fillMaxWidth(), singleLine = true)
            brands.filter { it.contains(coverBrand, true) && coverBrand.isNotBlank() }.take(6).forEach { b ->
                Text(b, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().clickable { coverBrand = b }.padding(vertical = 3.dp))
            }
            Divider(Modifier.padding(vertical = 4.dp))
            Text("Categories - tick what he supplies", fontWeight = FontWeight.Bold, fontSize = 11.sp)
            cats.forEach { cat ->
                val on = coverCats.contains(cat)
                Row(Modifier.fillMaxWidth().clickable { coverCats = if(on) coverCats - cat else coverCats + cat }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = on, onCheckedChange = { coverCats = if(on) coverCats - cat else coverCats + cat })
                    Text(cat, fontSize = 11.sp)
                }
            }
        } },
        confirmButton = { TextButton(onClick = {
            if(coverBrand.isNotBlank() && coverCats.isNotEmpty()) {
                scope.launch {
                    withContext(Dispatchers.IO) { saveCoverage(ctx, coverFor, coverBrand.trim().uppercase(), coverCats.toList()) }
                    coverFor = ""; coverBrand = ""; coverCats = emptySet(); reload++
                }
            } else Toast.makeText(ctx, "Choose a brand and at least one category", Toast.LENGTH_SHORT).show()
        }) { Text("SAVE") } },
        dismissButton = { TextButton(onClick = { coverFor = "" }) { Text("CANCEL") } }
    )

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("STOCK MASTER", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = { onDone() }) { Text("BACK") }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            listOf("models" to "Models", "brands" to "Brands", "distributors" to "Distributors").forEach { (id, label) ->
                Button(onClick = { tab = id }, modifier = Modifier.weight(1f).padding(horizontal = 2.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if(tab == id) Color(0xFF2E7D32) else Color.LightGray)) {
                    Text(label, fontSize = 9.sp, maxLines = 1, color = if(tab == id) Color.White else Color.Black)
                }
            }
        }

        if(tab == "models") {
            Card(Modifier.padding(vertical = 4.dp)) { Column(Modifier.padding(12.dp)) {
                OutlinedTextField(q, {q=it}, label={Text("Search model, SKU or brand")}, modifier=Modifier.fillMaxWidth(),
                    keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search))
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(fBrand, {fBrand=it.uppercase()}, label={Text("Brand")}, modifier=Modifier.weight(1f), singleLine = true)
                    Spacer(Modifier.width(4.dp))
                    OutlinedTextField(fCat, {fCat=it}, label={Text("Category")}, modifier=Modifier.weight(1f), singleLine = true)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = fOnlyNoSku, onCheckedChange = { fOnlyNoSku = it })
                    Text("Only models with no SKU", fontSize = 10.sp)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { q=""; fBrand=""; fCat=""; fOnlyNoSku=false }) { Text("CLEAR", fontSize = 9.sp) }
                }
                val hits = models.filter { matches(it) }
                val shown = if(q.isBlank() && fBrand.isBlank() && fCat.isBlank() && !fOnlyNoSku) models.take(20) else hits.take(30)
                Text(if(hits.size > shown.size) shown.size.toString() + " of " + hits.size + " - keep typing to narrow" else hits.size.toString() + " model(s)",
                    fontSize = 9.sp, color = Color.Gray)
                Text("SKUs in the database: " + countRows(ctx, "skus"), fontSize = 9.sp, color = Color(0xFF1565C0))
                Spacer(Modifier.height(4.dp))
                shown.forEach { m ->
                    val its = skusOf(m)
                    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(m.brand + "  " + m.name, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text(m.category + "  \u00b7  " + (if(its.isEmpty()) "no SKU yet" else its.size.toString() + " SKU"), fontSize = 9.sp,
                                    color = if(its.isEmpty()) Color(0xFFB26A00) else Color.Gray)
                            }
                            TextButton(onClick = { skuForModel = m; skuCode = "" }) { Text("+ SKU", fontSize = 9.sp) }
                            Text("\u00d7", fontSize = 11.sp, color = Color.Red, modifier = Modifier.clickable {
                                scope.launch { withContext(Dispatchers.IO) { deleteModel(ctx, m.id) }; reload++ }
                            }.padding(horizontal = 6.dp))
                        }
                        its.forEach { k -> Text("      SKU  " + k.sku, fontSize = 9.sp, color = Color(0xFF2E7D32)) }
                    }
                    Divider()
                }
            }}

            Card(Modifier.padding(vertical = 4.dp)) { Column(Modifier.padding(12.dp)) {
                Text("Add a model", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Row(Modifier.fillMaxWidth()) {
                    OutlinedTextField(newModelBrand, {newModelBrand=it.uppercase()}, label={Text("Brand")}, modifier=Modifier.weight(1f), singleLine = true)
                    Spacer(Modifier.width(4.dp))
                    OutlinedTextField(newModelCat, {newModelCat=it}, label={Text("Category")}, modifier=Modifier.weight(1f), singleLine = true)
                }
                OutlinedTextField(newModelName, {newModelName=it}, label={Text("Model, e.g. GALAXY F56 5G 8/128")}, modifier=Modifier.fillMaxWidth(), singleLine = true)
                Button(onClick = {
                    if(newModelBrand.isNotBlank() && newModelName.isNotBlank()) {
                        scope.launch {
                            withContext(Dispatchers.IO) { saveModel(ctx, Model(0, newModelBrand, newModelCat, newModelName)) }
                            Toast.makeText(ctx, "Model saved - now add its SKU", Toast.LENGTH_SHORT).show()
                            newModelName = ""; reload++
                        }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("ADD MODEL") }
            }}
        }

        if(tab == "brands") {
            Card(Modifier.padding(vertical = 4.dp)) { Column(Modifier.padding(12.dp)) {
                Text("Brands (" + brands.size + ")", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                OutlinedTextField(brandQ, {brandQ=it}, label={Text("Search brand")}, modifier=Modifier.fillMaxWidth())
                val shown = brands.filter { brandQ.isBlank() || it.contains(brandQ.trim(), true) }.take(40)
                Text(if(shown.size < brands.filter { brandQ.isBlank() || it.contains(brandQ.trim(), true) }.size) "40 of many - keep typing" else shown.size.toString() + " shown",
                    fontSize = 9.sp, color = Color.Gray)
                shown.forEach { b ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(b, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Text("\u00d7", fontSize = 12.sp, color = Color.Red, modifier = Modifier.clickable {
                            scope.launch { withContext(Dispatchers.IO) { deleteBrand(ctx, b) }; reload++ }
                        }.padding(horizontal = 8.dp))
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    OutlinedTextField(newBrand, {newBrand=it}, label={Text("Add brand")}, modifier=Modifier.weight(1f))
                    Button(onClick = {
                        val name = newBrand
                        scope.launch {
                            val added = withContext(Dispatchers.IO) { addBrand(ctx, name) }
                            Toast.makeText(ctx, if(added) name.trim().uppercase() + " added" else name.trim().uppercase() + " is already in the list", Toast.LENGTH_SHORT).show()
                            newBrand = ""; reload++
                        }
                    }) { Text("ADD") }
                }
            }}

            Card(Modifier.padding(vertical = 4.dp)) { Column(Modifier.padding(12.dp)) {
                Text("Categories (" + cats.size + ")", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                OutlinedTextField(catQ, {catQ=it}, label={Text("Search category")}, modifier=Modifier.fillMaxWidth())
                cats.filter { catQ.isBlank() || it.contains(catQ.trim(), true) }.take(40).forEach { c ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(c, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Text("\u00d7", fontSize = 12.sp, color = Color.Red, modifier = Modifier.clickable {
                            scope.launch { withContext(Dispatchers.IO) { deleteCategory(ctx, c) }; reload++ }
                        }.padding(horizontal = 8.dp))
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    OutlinedTextField(newCat, {newCat=it}, label={Text("Add category")}, modifier=Modifier.weight(1f))
                    Button(onClick = {
                        val name = newCat
                        scope.launch {
                            val added = withContext(Dispatchers.IO) { addCategory(ctx, name) }
                            Toast.makeText(ctx, if(added) name.trim() + " added" else name.trim() + " is already in the list", Toast.LENGTH_SHORT).show()
                            newCat = ""; reload++
                        }
                    }) { Text("ADD") }
                }
            }}
        }

        if(tab == "distributors") {
            Card(Modifier.padding(vertical = 4.dp)) { Column(Modifier.padding(12.dp)) {
                Text("Distributors (" + dists.size + ")", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                OutlinedTextField(distQ, {distQ=it}, label={Text("Search distributor")}, modifier=Modifier.fillMaxWidth())
                dists.filter { distQ.isBlank() || it.name.contains(distQ.trim(), true) }.forEach { d ->
                    val cover = loadCoverage(ctx, d.name)
                    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(d.name, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text("limit \u20b9" + fmt(d.creditLimit) + "  \u00b7  " + d.creditDays + " days", fontSize = 9.sp, color = Color.Gray)
                            }
                            TextButton(onClick = { coverFor = d.name; coverBrand = ""; coverCats = emptySet() }) { Text("SUPPLIES", fontSize = 9.sp) }
                            Text("\u00d7", fontSize = 12.sp, color = Color.Red, modifier = Modifier.clickable {
                                scope.launch { withContext(Dispatchers.IO) { deleteDistributor(ctx, d.id) }; reload++ }
                            }.padding(horizontal = 6.dp))
                        }
                        if(cover.isEmpty()) Text("Nothing set yet - tap SUPPLIES", fontSize = 9.sp, color = Color(0xFFB26A00))
                        else cover.forEach { pair ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(pair.first + " \u2192 " + pair.second.replace(",", ", "), fontSize = 9.sp, color = Color(0xFF2E7D32), modifier = Modifier.weight(1f))
                                Text("\u00d7", fontSize = 11.sp, color = Color.Red, modifier = Modifier.clickable {
                                    scope.launch { withContext(Dispatchers.IO) { deleteCoverage(ctx, d.name, pair.first) }; reload++ }
                                }.padding(horizontal = 6.dp))
                            }
                        }
                    }
                    Divider()
                }
                Row(Modifier.fillMaxWidth()) {
                    OutlinedTextField(newDist, {newDist=it}, label={Text("Name")}, modifier=Modifier.weight(1.3f), singleLine = true)
                    Spacer(Modifier.width(4.dp))
                    OutlinedTextField(newDistLimit, {newDistLimit=it}, label={Text("Credit \u20b9")}, modifier=Modifier.weight(1f), singleLine = true)
                    Spacer(Modifier.width(4.dp))
                    OutlinedTextField(newDistDays, {newDistDays=it}, label={Text("Days")}, modifier=Modifier.weight(0.7f), singleLine = true)
                }
                Button(onClick = {
                    if(newDist.isNotBlank()) {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                saveDistributor(ctx, Distributor(0, newDist.trim(), "", "", "", newDistLimit.toDoubleOrNull() ?: 0.0,
                                    newDistDays.toIntOrNull() ?: 30, "", true))
                            }
                            newDist=""; newDistLimit=""; newDistDays="30"; reload++
                        }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("ADD DISTRIBUTOR") }
            }}
        }
    }
}

@Composable
fun GrnScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var reload by remember { mutableStateOf(0) }
    var showReceive by rememberSaveable { mutableStateOf(false) }
    var serialTarget by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    val grns by produceState(initialValue = emptyList<Grn>(), reload) { value = withContext(Dispatchers.IO) { loadGrns(ctx) } }

    if(showReceive) { ReceiveScreen { showReceive = false; reload++ }; return }
    if(serialTarget != null) {
        SerialScreen(serialTarget!!.first, serialTarget!!.second) { serialTarget = null; reload++ }
        return
    }

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("RECEIVED (GRN)", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = { onDone() }) { Text("BACK") }
        }
        Text("One challan, one brand, one number. A GRN is pending until its serials are entered.", fontSize = 10.sp, color = Color.Gray)
        Spacer(Modifier.height(6.dp))
        Button(onClick = { showReceive = true }, modifier = Modifier.fillMaxWidth()) { Text("NEW RECEIPT") }
        Spacer(Modifier.height(8.dp))
        if(grns.isEmpty()) Text("Nothing received yet.", fontSize = 11.sp)
        grns.forEach { g ->
            Card(Modifier.padding(vertical = 4.dp)) { Column(Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    Text(g.no, fontWeight = FontWeight.Bold)
                    Text("\u20b9" + fmt(g.total), fontWeight = FontWeight.Bold)
                }
                Text(g.date + "  \u00b7  " + g.distributor + (if(g.brand.isBlank()) "" else "  \u00b7  " + g.brand), fontSize = 10.sp, maxLines = 1)
                val pending = pendingUnitCount(ctx, g.id)
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text(if(pending > 0) "PENDING " + pending + " serials" else "POSTED \u00b7 stock in",
                        fontSize = 10.sp, color = if(pending > 0) Color(0xFFB26A00) else Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                    if(pending > 0) TextButton(onClick = {
                        val line = grnLines(ctx, g.id).firstOrNull { l -> unitsForGrn(ctx, g.id).any { it.status == "PENDING" && it.sku == l.second.sku } }
                        if(line != null) serialTarget = g.id to line.first
                    }) { Text("ENTER SERIALS", fontSize = 10.sp) }
                }
                grnLines(ctx, g.id).forEach { (_, l) ->
                    Text(l.sku + "  \u00b7  " + l.model + "  \u00d7 " + l.units + "  \u00b7  \u20b9" + fmt(l.price), fontSize = 9.sp, color = Color.Gray, maxLines = 1)
                }
            }}
        }
    }
}

@Composable
fun ReceiveScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var dist by rememberSaveable { mutableStateOf("") }
    var brand by rememberSaveable { mutableStateOf("") }
    var no by rememberSaveable { mutableStateOf("") }
    var docType by rememberSaveable { mutableStateOf("Challan") }
    var paper by rememberSaveable { mutableStateOf("") }
    var pickDist by remember { mutableStateOf(false) }
    var pickBrand by remember { mutableStateOf(false) }
    var brandQuery by remember { mutableStateOf("") }
    var distQuery by remember { mutableStateOf("") }
    var lSku by rememberSaveable { mutableStateOf("") }
    var lModel by rememberSaveable { mutableStateOf("") }
    var lVariant by rememberSaveable { mutableStateOf("") }
    var lHsn by rememberSaveable { mutableStateOf("") }
    var lGst by rememberSaveable { mutableStateOf("18") }
    var lUnits by rememberSaveable { mutableStateOf("1") }
    var lPrice by rememberSaveable { mutableStateOf("") }
    var lines by remember { mutableStateOf(listOf<GrnLine>()) }
    var newSkuFor by remember { mutableStateOf<String?>(null) }
    var nsModel by remember { mutableStateOf("") }
    var nsVariant by remember { mutableStateOf("") }
    var nsCategory by remember { mutableStateOf("Mobile") }
    var nsHsn by remember { mutableStateOf("") }
    var nsGst by remember { mutableStateOf("18") }
    var saving by remember { mutableStateOf(false) }
    val dists by produceState(initialValue = emptyList<Distributor>(), ctx) { value = withContext(Dispatchers.IO) { loadDistributors(ctx) } }
    val brands by produceState(initialValue = emptyList<String>(), ctx) { value = withContext(Dispatchers.IO) { loadBrands(ctx) } }
    val cats by produceState(initialValue = emptyList<String>(), ctx) { value = withContext(Dispatchers.IO) { loadCategories(ctx) } }

    val taxable = lines.sumOf { it.units * it.price }
    val gstTotal = lines.sumOf { it.units * it.price * it.gstRate / 100.0 }
    val total = taxable + gstTotal
    val paperVal = paper.toDoubleOrNull() ?: 0.0
    val gap = if(paperVal <= 0.0) 0.0 else paperVal - total

    if(pickDist) AlertDialog(
        onDismissRequest = { pickDist = false },
        title = { Text("Distributor") },
        text = { Column {
            if(dists.isEmpty()) Text("No distributors yet. Add one under Stock \u2192 MASTER.", fontSize = 11.sp)
            if(dists.size > 6) OutlinedTextField(distQuery, {distQuery=it}, label={Text("Search")}, modifier=Modifier.fillMaxWidth())
            Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 360.dp)) {
                dists.filter { distQuery.isBlank() || it.name.contains(distQuery.trim(), true) }.forEach { d ->
                    Text(d.name, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().clickable { dist = d.name; pickDist = false; distQuery = "" }.padding(vertical = 6.dp))
                }
            }
        } },
        confirmButton = { TextButton(onClick = { pickDist = false; distQuery = "" }) { Text("CLOSE") } })

    if(pickBrand) {
        val hisBrands = if(dist.isBlank()) emptyList() else brandsForDistributor(ctx, dist)
        val offered = if(hisBrands.isEmpty()) brands else brands.filter { b -> hisBrands.any { it.equals(b, true) } }
        AlertDialog(
            onDismissRequest = { pickBrand = false },
            title = { Text("Brand") },
            text = { Column {
                if(dist.isBlank()) {
                    Text("Choose the distributor first - his brands are what you may receive.", fontSize = 10.sp, color = Color(0xFFB26A00))
                } else if(hisBrands.isEmpty()) {
                    Text("Nothing set for " + dist + " yet, so every brand is offered. Set his coverage under Stock \u2192 MASTER \u2192 SUPPLIES.", fontSize = 10.sp, color = Color(0xFFB26A00))
                } else {
                    Text("What " + dist + " supplies", fontSize = 10.sp, color = Color(0xFF2E7D32))
                }
                OutlinedTextField(brandQuery, {brandQuery=it}, label={Text("Search")}, modifier=Modifier.fillMaxWidth(),
                    keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search))
                Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 340.dp)) {
                    offered.filter { brandQuery.isBlank() || it.contains(brandQuery.trim(), true) }.forEach { b ->
                        Text(b, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().clickable { brand = b; pickBrand = false; brandQuery = "" }.padding(vertical = 6.dp))
                    }
                }
            } },
            confirmButton = { TextButton(onClick = { pickBrand = false; brandQuery = "" }) { Text("CLOSE") } })
    }

    if(newSkuFor != null) AlertDialog(
        onDismissRequest = { newSkuFor = null },
        title = { Text("New SKU - create it here") },
        text = { Column {
            Text("SKU  " + (newSkuFor ?: ""), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("Brand: " + brand, fontSize = 10.sp, color = Color.Gray)
            OutlinedTextField(nsModel, {nsModel=it}, label={Text("Model, e.g. GALAXY F56 5G 8/128")}, modifier=Modifier.fillMaxWidth(), singleLine = true)
            // pick one that already exists instead of retyping it
            val existing = loadModels(ctx).filter {
                it.brand.equals(brand, true) && (nsModel.isBlank() || it.name.contains(nsModel.trim(), true))
            }.take(6)
            if(existing.isNotEmpty()) {
                Text("Or pick an existing model", fontSize = 9.sp, color = Color.Gray)
                Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 110.dp)) {
                    existing.forEach { m ->
                        Text(m.name, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().clickable { nsModel = m.name; nsCategory = m.category }.padding(vertical = 3.dp))
                    }
                }
            }
            Row {
                OutlinedTextField(nsCategory, {nsCategory=it}, label={Text("Category")}, modifier=Modifier.weight(1f), singleLine = true)
                Spacer(Modifier.width(4.dp))
                OutlinedTextField(nsGst, {nsGst=it}, label={Text("GST %")}, modifier=Modifier.weight(0.6f), singleLine = true)
            }
            OutlinedTextField(nsHsn, {nsHsn=it}, label={Text("HSN")}, modifier=Modifier.fillMaxWidth(), singleLine = true)
            Text("Saving creates the model if it is new, and adds the line to this challan.", fontSize = 9.sp, color = Color(0xFF2E7D32))
        } },
        confirmButton = { TextButton(onClick = {
            val sku = newSkuFor ?: return@TextButton
            if(nsModel.isBlank()) { Toast.makeText(ctx, "The model name is needed", Toast.LENGTH_SHORT).show(); return@TextButton }
            val units = lUnits.toIntOrNull() ?: 1
            val price = lPrice.toDoubleOrNull() ?: 0.0
            scope.launch {
                val err = withContext(Dispatchers.IO) {
                    saveSku(ctx, SkuInfo(sku.trim(), brand, nsCategory, nsModel, "", nsHsn,
                        nsGst.toDoubleOrNull() ?: 18.0, 0.0, identTypeFor(nsCategory)))
                }
                if(err != null) { Toast.makeText(ctx, "Could not save: " + err, Toast.LENGTH_LONG).show(); return@launch }
                // straight onto the challan, no second trip
                if(price > 0.0) {
                    lines = lines + GrnLine(sku.trim(), nsModel, "", nsCategory, nsHsn,
                        nsGst.toDoubleOrNull() ?: 18.0, units, price, identTypeFor(nsCategory))
                    lSku = ""; lModel = ""; lUnits = "1"; lPrice = ""
                } else {
                    lSku = sku.trim(); lModel = nsModel; lHsn = nsHsn; lGst = nsGst
                }
                newSkuFor = null
                Toast.makeText(ctx, "Model and SKU saved" + (if(price > 0.0) ", line added" else ""), Toast.LENGTH_SHORT).show()
            }
        }) { Text("SAVE AND ADD") } },
        dismissButton = { TextButton(onClick = { newSkuFor = null }) { Text("CANCEL") } }
    )

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("NEW RECEIPT", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = { onDone() }) { Text("BACK") }
        }
        Card(Modifier.padding(vertical = 5.dp)) { Column(Modifier.padding(10.dp)) {
            Button(onClick = { pickDist = true }, modifier = Modifier.fillMaxWidth()) { Text(if(dist.isBlank()) "CHOOSE DISTRIBUTOR" else dist) }
            Button(onClick = { pickBrand = true }, modifier = Modifier.fillMaxWidth()) { Text(if(brand.isBlank()) "CHOOSE BRAND" else brand) }
            Row {
                OutlinedTextField(no, {no=it}, label={Text("Challan no")}, modifier=Modifier.weight(1f), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
                Spacer(Modifier.width(4.dp))
                OutlinedTextField(docType, {docType=it}, label={Text("Challan / Invoice")}, modifier=Modifier.weight(1f))
            }
            OutlinedTextField(paper, {paper=it}, label={Text("Amount on the paper")}, modifier=Modifier.fillMaxWidth(),
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number, imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
        }}

        Card(Modifier.padding(vertical = 5.dp)) { Column(Modifier.padding(10.dp)) {
            Text("Add a line", fontWeight = FontWeight.Bold)
            OutlinedTextField(lSku, {
                lSku = it
                val k = findSku(ctx, it)
                if(k != null) { lModel = k.model; lVariant = k.variant; lHsn = k.hsn; lGst = k.gstRate.toString() }
            }, label={Text("SKU from the challan, e.g. SM-E556B 8/128")}, modifier=Modifier.fillMaxWidth())
            val known = findSku(ctx, lSku)
            if(known != null) Text("= " + known.model + (if(known.variant.isBlank()) "" else "  \u00b7  " + known.variant), fontSize = 10.sp, color = Color(0xFF2E7D32))
            else if(lSku.isNotBlank()) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("New SKU", fontSize = 10.sp, color = Color(0xFFB26A00), modifier = Modifier.weight(1f))
                TextButton(onClick = { newSkuFor = lSku.trim(); nsModel = lModel; nsCategory = "Mobile" }) { Text("CREATE IT", fontSize = 10.sp) }
            }
            OutlinedTextField(lModel, {lModel=it}, label={Text("Model, e.g. GALAXY F56 5G 8/128")}, modifier=Modifier.fillMaxWidth(),
                keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
            Row {
                OutlinedTextField(lUnits, {lUnits=it}, label={Text("Units")}, modifier=Modifier.weight(0.6f), keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                Spacer(Modifier.width(4.dp))
                OutlinedTextField(lPrice, {lPrice=it}, label={Text("Price per unit")}, modifier=Modifier.weight(1f), keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
            }
            Button(onClick = {
                val sku = lSku.trim(); val k = findSku(ctx, sku)
                val units = lUnits.toIntOrNull() ?: 0
                val price = lPrice.toDoubleOrNull() ?: 0.0
                if(sku.isNotBlank() && units > 0 && price > 0) {
                    val cat = k?.category ?: "Mobile"
                    lines = lines + GrnLine(sku, lModel.ifBlank { k?.model ?: sku }, lVariant.ifBlank { k?.variant ?: "" },
                        cat, lHsn.ifBlank { k?.hsn ?: "" }, k?.gstRate ?: (lGst.toDoubleOrNull() ?: 18.0),
                        units, price, identTypeFor(cat))
                    lSku = ""; lModel = ""; lVariant = ""; lUnits = "1"; lPrice = ""
                } else if(k == null) {
                    nsModel = lModel; nsVariant = lVariant
                    newSkuFor = sku
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("ADD LINE") }
        }}

        if(lines.isNotEmpty()) Card(Modifier.padding(vertical = 5.dp)) { Column(Modifier.padding(10.dp)) {
            lines.forEachIndexed { i, l ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(l.sku + "  \u00d7 " + l.units, fontSize = 11.sp, maxLines = 1)
                        Text(l.model + (if(l.variant.isBlank()) "" else " " + l.variant) + "  \u00b7  " + identLabel(l.identType), fontSize = 9.sp, color = Color.Gray, maxLines = 1)
                    }
                    Text("\u20b9" + fmt(l.units * l.price), fontSize = 11.sp)
                    TextButton(onClick = { lines = lines.filterIndexed { j, _ -> j != i } }) { Text("\u00d7", color = Color.Red) }
                }
            }
            Divider(Modifier.padding(vertical = 4.dp))
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Taxable", fontSize = 11.sp); Text("\u20b9" + fmt(taxable), fontSize = 11.sp) }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("GST", fontSize = 11.sp); Text("\u20b9" + fmt(gstTotal), fontSize = 11.sp) }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Total", fontWeight = FontWeight.Bold); Text("\u20b9" + fmt(total), fontWeight = FontWeight.Bold) }
            if(paperVal > 0.0) Text(
                if(abs(gap) < 1.0) "\u2713 matches the challan" else "paper \u20b9" + fmt(paperVal) + "  \u00b7  gap \u20b9" + fmt(gap),
                fontSize = 10.sp, color = if(abs(gap) < 1.0) Color(0xFF2E7D32) else Color.Red)
            Button(onClick = {
                if(dist.isBlank() || brand.isBlank() || no.isBlank()) {
                    Toast.makeText(ctx, "Distributor, brand and challan number are required", Toast.LENGTH_SHORT).show()
                } else if(grnExists(ctx, dist, brand, no)) {
                    Toast.makeText(ctx, "Challan " + no + " already received from " + dist + " for " + brand, Toast.LENGTH_LONG).show()
                } else {
                    saving = true
                    scope.launch {
                        val g = Grn(0, no.trim(), dist, brand, SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH).format(Date()),
                            docType.trim(), paperVal, taxable, gstTotal, total,
                            dists.firstOrNull { it.name == dist }?.creditDays ?: 30, "PENDING")
                        val id = withContext(Dispatchers.IO) { saveGrn(ctx, g, lines) }
                        saving = false
                        if(id > 0) {
                            Toast.makeText(ctx, "GRN " + no + " saved - " + lines.sumOf { it.units } + " units received", Toast.LENGTH_LONG).show()
                            onDone()
                        } else Toast.makeText(ctx, "Could not save the receipt", Toast.LENGTH_LONG).show()
                    }
                }
            }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text(if(saving) "SAVING..." else "SAVE RECEIPT") }
        }}
    }
}

@Composable
fun SerialScreen(grnId: Long, lineId: Long, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    val grn = remember { loadGrns(ctx).firstOrNull { it.id == grnId } }
    val line = remember { grnLines(ctx, grnId).firstOrNull { it.first == lineId }?.second }
    val pending = remember { unitsForGrn(ctx, grnId).count { it.status == "PENDING" && it.sku == (line?.sku ?: "") } }

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("ENTER SERIALS", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = { onDone() }) { Text("BACK") }
        }
        Text((grn?.no ?: "") + "  \u00b7  " + (line?.sku ?: ""), fontSize = 11.sp, color = Color.Gray)
        Text((line?.model ?: "") + (if((line?.variant ?: "").isBlank()) "" else " " + line!!.variant), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(identLabel(line?.identType ?: "SERIAL") + "  \u00b7  " + pending + " still pending", fontSize = 10.sp, color = Color(0xFFB26A00))
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(text, {text=it}, label={Text("One per line. For an AC: indoor, outdoor")}, minLines = 6,
            modifier = Modifier.fillMaxWidth())
        Text("Paste or type the numbers from the boxes, one per line. Duplicates are ignored.", fontSize = 9.sp, color = Color.Gray)
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            val rows = text.split("\n").map { it.trim() }.filter { it.isNotBlank() }.map {
                val parts = it.split(",", ";").map { p -> p.trim() }
                (parts.getOrNull(0) ?: "") to (parts.getOrNull(1) ?: "")
            }
            if(rows.isEmpty()) { Toast.makeText(ctx, "Nothing to save", Toast.LENGTH_SHORT).show(); return@Button }
            scope.launch {
                val n = withContext(Dispatchers.IO) { saveSerials(ctx, grnId, lineId, rows.distinctBy { it.first }) }
                Toast.makeText(ctx, if(n > 0) "$n serials recorded" else "Nothing saved", Toast.LENGTH_LONG).show()
                onDone()
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("SAVE SERIALS") }
    }
}
@Composable
fun PurchaseScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val prefs = remember { ctx.getSharedPreferences("penkhata_data", Context.MODE_PRIVATE) }
    var dist by rememberSaveable { mutableStateOf("") }
    var invNo by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var pickDist by remember { mutableStateOf(false) }
    // one line being typed
    var lModel by rememberSaveable { mutableStateOf("") }
    var lBrand by rememberSaveable { mutableStateOf("") }
    var lCat by rememberSaveable { mutableStateOf("Mobile") }
    var lHsn by rememberSaveable { mutableStateOf("") }
    var lQty by rememberSaveable { mutableStateOf("1") }
    var lCost by rememberSaveable { mutableStateOf("") }
    var lGst by rememberSaveable { mutableStateOf("18") }
    var lSerials by rememberSaveable { mutableStateOf("") }
    var lines by remember { mutableStateOf(listOf<PurchaseLine>()) }
    var saving by remember { mutableStateOf(false) }
    val dists by produceState(initialValue = emptyList<Distributor>(), ctx) { value = withContext(Dispatchers.IO) { loadDistributors(ctx) } }
    val recent by produceState(initialValue = emptyList<Purchase>(), ctx) { value = withContext(Dispatchers.IO) { loadPurchases(ctx) } }

    if(pickDist) AlertDialog(
        onDismissRequest = { pickDist = false },
        title = { Text("Distributor") },
        text = { Column {
            if(dists.isEmpty()) Text("No distributors yet - add one under Stock -> MASTER DATA.", fontSize = 11.sp)
            dists.forEach { d ->
                Text(d.name, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().clickable { dist = d.name; pickDist = false }.padding(vertical = 6.dp))
            }
        } },
        confirmButton = { TextButton(onClick = { pickDist = false }) { Text("CLOSE") } }
    )

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("PURCHASE", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = { onDone() }) { Text("BACK") }
        }
        Card(Modifier.padding(vertical = 5.dp)) { Column(Modifier.padding(10.dp)) {
            Button(onClick = { pickDist = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if(dist.isBlank()) "CHOOSE DISTRIBUTOR" else dist)
            }
            OutlinedTextField(invNo, {invNo=it}, label={Text("Distributor invoice no")}, modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
            OutlinedTextField(note, {note=it}, label={Text("Note (optional)")}, modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
        }}

        Card(Modifier.padding(vertical = 5.dp)) { Column(Modifier.padding(10.dp)) {
            Text("Add a line", fontWeight = FontWeight.Bold)
            OutlinedTextField(lModel, {lModel=it}, label={Text("Model")}, modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
            Row {
                OutlinedTextField(lBrand, {lBrand=it}, label={Text("Brand")}, modifier=Modifier.weight(1f), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
                Spacer(Modifier.width(4.dp))
                OutlinedTextField(lCat, {lCat=it}, label={Text("Category")}, modifier=Modifier.weight(1f), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
            }
            Row {
                OutlinedTextField(lQty, {lQty=it}, label={Text("Qty")}, modifier=Modifier.weight(0.6f), keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number, imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
                Spacer(Modifier.width(4.dp))
                OutlinedTextField(lCost, {lCost=it}, label={Text("Cost \u20b9 ex-GST")}, modifier=Modifier.weight(1f), keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number, imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
                Spacer(Modifier.width(4.dp))
                OutlinedTextField(lGst, {lGst=it}, label={Text("GST %")}, modifier=Modifier.weight(0.6f), keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number, imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
            }
            Row {
                OutlinedTextField(lHsn, {lHsn=it}, label={Text("HSN")}, modifier=Modifier.weight(1f), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
            }
            OutlinedTextField(lSerials, {lSerials=it}, label={Text("IMEI / serials - one per line")}, minLines=2, modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
            Text("Serials override quantity: three serials means three units.", fontSize = 9.sp, color = Color.Gray)
            Button(onClick = {
                if(lModel.isNotBlank() && (lCost.toDoubleOrNull() ?: 0.0) > 0.0) {
                    val serialCount = lSerials.split("\n", ",", " ").map { it.trim() }.filter { it.isNotBlank() }.distinct().size
                    val qty = if(serialCount > 0) serialCount else (lQty.toIntOrNull() ?: 1)
                    lines = lines + PurchaseLine(lModel.trim(), lBrand.trim().uppercase(), lCat.trim(), lHsn.trim(), qty,
                        lCost.toDoubleOrNull() ?: 0.0, lGst.toDoubleOrNull() ?: 18.0, lSerials.trim())
                    lModel=""; lCost=""; lSerials=""; lQty="1"
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("ADD LINE") }
        }}

        if(lines.isNotEmpty()) Card(Modifier.padding(vertical = 5.dp)) { Column(Modifier.padding(10.dp)) {
            Text("Lines (" + lines.size + ")", fontWeight = FontWeight.Bold)
            lines.forEachIndexed { i, l ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(l.model + "  \u00d7 " + l.qty, fontSize = 11.sp, maxLines = 1)
                        Text(l.brand + " \u00b7 " + l.category + " \u00b7 \u20b9" + fmt(l.cost) + " + " + fmt(l.gstRate) + "%", fontSize = 9.sp, color = Color.Gray, maxLines = 1)
                    }
                    Text("\u20b9" + fmt(l.qty * l.cost * (1 + l.gstRate / 100.0)), fontSize = 11.sp)
                    TextButton(onClick = { lines = lines.filterIndexed { j, _ -> j != i } }) { Text("\u00d7", color = Color.Red) }
                }
            }
            Divider(Modifier.padding(vertical = 4.dp))
            val taxable = purchaseTaxable(lines)
            val gst = purchaseGst(lines)
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Taxable", fontSize = 11.sp); Text("\u20b9" + fmt(taxable), fontSize = 11.sp) }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("GST (input credit)", fontSize = 11.sp); Text("\u20b9" + fmt(gst), fontSize = 11.sp) }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Invoice total", fontWeight = FontWeight.Bold); Text("\u20b9" + fmt(taxable + gst), fontWeight = FontWeight.Bold) }
            Button(onClick = {
                if(dist.isBlank()) {
                    Toast.makeText(ctx, "Choose the distributor first", Toast.LENGTH_SHORT).show()
                } else {
                    saving = true
                    scope.launch {
                        val p = Purchase(System.currentTimeMillis(), dist, invNo.trim(), SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH).format(Date()),
                            taxable, gst, taxable + gst, dists.firstOrNull { it.name == dist }?.creditDays ?: 0, note.trim(), lines)
                        val ok = withContext(Dispatchers.IO) { savePurchase(ctx, p) }
                        saving = false
                        if(ok) {
                            Toast.makeText(ctx, "Purchase saved, " + lines.sumOf { it.qty } + " units added to stock", Toast.LENGTH_LONG).show()
                            onDone()
                        } else {
                            Toast.makeText(ctx, "Could not save the purchase", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text(if(saving) "SAVING..." else "SAVE PURCHASE") }
        }}

        if(recent.isNotEmpty()) Card(Modifier.padding(vertical = 5.dp)) { Column(Modifier.padding(10.dp)) {
            Text("Recent purchases", fontWeight = FontWeight.Bold)
            recent.take(10).forEach { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(p.distributor + "  \u00b7  " + p.invoiceNo, fontSize = 11.sp, maxLines = 1)
                        Text(p.date + (if(p.creditDays > 0) "  \u00b7  " + p.creditDays + " days credit" else ""), fontSize = 9.sp, color = Color.Gray)
                    }
                    Text("\u20b9" + fmt(p.total), fontSize = 11.sp)
                }
            }
        }}
    }
}

@Composable fun StockScreen() {
    var showPurchase by rememberSaveable { mutableStateOf(false) }
    var showAudit by rememberSaveable { mutableStateOf(false) }
    var showGrn by rememberSaveable { mutableStateOf(false) }
    var showMaster by rememberSaveable { mutableStateOf(false) }
    if(showPurchase) { PurchaseScreen { showPurchase = false }; return }
    if(showAudit) { StockAuditScreen { showAudit = false }; return }
    if(showGrn) { GrnScreen { showGrn = false }; return }
    if(showMaster) { StockMasterScreen { showMaster = false }; return }
    val focusManager = LocalFocusManager.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableStateOf(0) }
    val (stock, loadingStock) = loadState(emptyList<StockEntry>(), reload) { withContext(Dispatchers.IO) { loadStock(ctx) } }
    var model by rememberSaveable { mutableStateOf("") }; var imei by rememberSaveable { mutableStateOf("") }
    var hsn by rememberSaveable { mutableStateOf("") }; var cost by rememberSaveable { mutableStateOf("") }
    var mrp by rememberSaveable { mutableStateOf("") }; var qty by rememberSaveable { mutableStateOf("1") }
    var supplier by rememberSaveable { mutableStateOf("") }
    var brand by rememberSaveable { mutableStateOf("") }
    var newBrand by rememberSaveable { mutableStateOf("") }
    var newCat by rememberSaveable { mutableStateOf("") }
    var newDist by rememberSaveable { mutableStateOf("") }
    var newDistLimit by rememberSaveable { mutableStateOf("") }
    var cls by rememberSaveable { mutableStateOf("Mobile") }
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
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Button(onClick = { showGrn = true }, modifier = Modifier.weight(1f)) {
                Text("RECEIVED", fontSize = 11.sp, maxLines = 1)
            }
            Spacer(Modifier.width(5.dp))
            OutlinedButton(onClick = { showAudit = true }, modifier = Modifier.weight(1f)) {
                Text("AUDIT", fontSize = 11.sp, maxLines = 1)
            }
            Spacer(Modifier.width(5.dp))
            OutlinedButton(onClick = { showMaster = true }, modifier = Modifier.weight(1f)) {
                Text("MASTER", fontSize = 11.sp, maxLines = 1)
            }
        }
        if(loadingStock) LoadingRow("Reading stock...")
        Card(Modifier.padding(vertical = 10.dp)) { Column(Modifier.padding(16.dp)) {
            Text("Stock in hand", fontWeight = FontWeight.Bold)
            Divider(Modifier.padding(vertical = 5.dp))
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Models"); Text("${stock.size}") }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Units in hand"); Text("$totalUnits") }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Cost value (what you paid)"); Text("\u20b9${fmt(totalValue)}") }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Taxable value (ex-GST)"); Text("\u20b9${fmt(totalValue)}") }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("MRP value (shelf price)"); Text("\u20b9${fmt(stock.sumOf { it.mrp * it.qty })}") }
            if(lowCount > 0) Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Low stock (1 or less)", color = Color.Red); Text("$lowCount", color = Color.Red) }
            val awaiting = pendingIdentCount(ctx)
            if(awaiting > 0) Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Awaiting serials", color = Color(0xFFB26A00)); Text("$awaiting units", color = Color(0xFFB26A00)) }
            Divider(Modifier.padding(vertical = 5.dp))
            Text("Brand wise", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            val byBrand = stock.groupBy { if(it.brand.isBlank()) "UNBRANDED" else it.brand }
            byBrand.entries.sortedByDescending { e -> e.value.sumOf { it.cost * it.qty } }.take(12).forEach { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), Arrangement.SpaceBetween) {
                    Text(e.key + "  (" + e.value.sumOf { it.qty } + ")", fontSize = 10.sp)
                    Text("\u20b9" + fmt(e.value.sumOf { it.cost * it.qty }), fontSize = 10.sp)
                }
            }
            Divider(Modifier.padding(vertical = 5.dp))
            Text("Ageing (days in stock)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            val today = System.currentTimeMillis()
            val ageOf = { e: StockEntry -> try { (today - SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH).parse(e.addedOn)!!.time) / 86400000L } catch(ex: Exception) { 0L } }
            val buckets = listOf("0-30" to (0L..30L), "31-60" to (31L..60L), "61-90" to (61L..90L), "90+" to (91L..100000L))
            buckets.forEach { (label, range) ->
                val inBucket = stock.filter { ageOf(it) in range }
                if(inBucket.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), Arrangement.SpaceBetween) {
                    Text(label + " days  (" + inBucket.sumOf { it.qty } + " units)", fontSize = 10.sp)
                    Text("\u20b9" + fmt(inBucket.sumOf { it.cost * it.qty }), fontSize = 10.sp, color = if(label == "90+") Color.Red else Color.Unspecified)
                }
            }
        }}
        stock.sortedBy { it.model.lowercase() }.forEach { e ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(e.model, fontWeight = FontWeight.Bold, maxLines = 2)
                    if(e.imeiLabel().isNotBlank()) Text(e.imeiLabel(), fontSize = 10.sp, maxLines = 2)
                    Text((if(e.brand.isBlank()) "" else e.brand + "  \u00b7  ") + (if(e.cls.isBlank()) "" else e.cls + "  \u00b7  ") + "in since " + e.addedOn, fontSize = 10.sp, maxLines = 1)
                    Text("Cost \u20b9${fmt(e.cost)}  \u00b7  MRP \u20b9${fmt(e.mrp)}  \u00b7  value \u20b9${fmt(e.cost * e.qty)}", fontSize = 10.sp, maxLines = 1)
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
// A plain bar chart, drawn with boxes: no library, no layout surprises.
@Composable
fun BarChart(data: List<Pair<String, Double>>, barColor: Color = Color(0xFF2E7D32)) {
    if(data.isEmpty()) return
    val max = data.maxOfOrNull { it.second } ?: 0.0
    Row(Modifier.fillMaxWidth().height(130.dp), verticalAlignment = Alignment.Bottom) {
        data.forEach { (label, value) ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if(value <= 0.0) "" else compactAmount(value), fontSize = 7.sp, maxLines = 1)
                val h = if(max <= 0.0) 2.dp else (value / max * 90.0).coerceAtLeast(2.0).dp
                Box(Modifier.width(18.dp).height(h).background(barColor))
                Spacer(Modifier.height(2.dp))
                Text(label, fontSize = 7.sp, maxLines = 1)
            }
        }
    }
}
fun compactAmount(v: Double): String = when {
    v >= 10000000 -> String.format("%.1fCr", v / 10000000)
    v >= 100000 -> String.format("%.1fL", v / 100000)
    v >= 1000 -> String.format("%.0fk", v / 1000)
    else -> String.format("%.0f", v)
}
// Sales for the last n months, oldest first, for the trend chart.
fun monthlySales(ctx: Context, months: Int): List<Pair<String, Double>> {
    val out = mutableListOf<Pair<String, Double>>()
    try {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        for(i in months - 1 downTo 0) {
            val c = cal.clone() as Calendar
            c.add(Calendar.MONTH, -i)
            c.set(Calendar.DAY_OF_MONTH, 1); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
            val start = c.timeInMillis
            c.add(Calendar.MONTH, 1)
            val end = c.timeInMillis
            var sum = 0.0
            db(ctx).readableDatabase.rawQuery("SELECT total FROM bills WHERE quote=0 AND cancelled=0 AND ms>=? AND ms<?",
                arrayOf(start.toString(), end.toString())).use { cur -> while(cur.moveToNext()) sum += cur.getDouble(0) }
            out.add(SimpleDateFormat("MMM", Locale.ENGLISH).format(Date(start)) to sum)
        }
    } catch(e: Exception) { }
    return out
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
    val xlsxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
        pickerInFlight = false
        if(uri != null) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(exportWorkbook(ctx)) }
                Toast.makeText(ctx, "Workbook exported", Toast.LENGTH_LONG).show()
            } catch(e: Exception) { Toast.makeText(ctx, "Export failed: " + (e.message ?: "unknown"), Toast.LENGTH_LONG).show() }
        }
    }
    val registerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
        pickerInFlight = false
        if(uri != null) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(buildRegisterXlsx(ctx, startMs, endMs)) }
                Toast.makeText(ctx, "Installation register exported", Toast.LENGTH_LONG).show()
            } catch(e: Exception) { Toast.makeText(ctx, "Export failed: " + (e.message ?: "unknown"), Toast.LENGTH_LONG).show() }
        }
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        pickerInFlight = false
        if(uri != null) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(exportCsvBills(ctx, startMs, endMs).toByteArray()) }
                Toast.makeText(ctx, "CSV exported", Toast.LENGTH_LONG).show()
            } catch(e: Exception) { Toast.makeText(ctx, "Export failed: " + (e.message ?: "unknown"), Toast.LENGTH_LONG).show() }
        }
    }
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
        Card(Modifier.padding(vertical = 5.dp)) { Column(Modifier.padding(16.dp)) {
            Text("Export", fontWeight = FontWeight.Bold)
            Divider(Modifier.padding(vertical = 5.dp))
            Text("XLSX carries everything: bills, lines, ledger, stock, expenses, settings. CSV carries this month's bills.", fontSize = 10.sp)
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                pickerInFlight = true
                xlsxLauncher.launch("PenKhata-export-" + SimpleDateFormat("yyyyMMdd", Locale.ENGLISH).format(Date()) + ".xlsx")
            }, modifier = Modifier.fillMaxWidth()) { Text("EXPORT XLSX") }
            Spacer(Modifier.height(6.dp))
            Button(onClick = {
                pickerInFlight = true
                csvLauncher.launch("PenKhata-bills-" + SimpleDateFormat("MMMyyyy", Locale.ENGLISH).format(Date(startMs)) + ".csv")
            }, modifier = Modifier.fillMaxWidth()) { Text("EXPORT CSV (THIS MONTH)") }
            Spacer(Modifier.height(6.dp))
            Button(onClick = {
                pickerInFlight = true
                registerLauncher.launch("Installation-Register-" + SimpleDateFormat("MMMyyyy", Locale.ENGLISH).format(Date(startMs)) + ".xlsx")
            }, modifier = Modifier.fillMaxWidth()) { Text("INSTALLATION REGISTER (XLSX)") }
        }}
        val trend by produceState(initialValue = emptyList<Pair<String, Double>>(), offset) {
            value = withContext(Dispatchers.IO) { monthlySales(ctx, 6) }
        }
        Card(Modifier.padding(vertical = 5.dp)) { Column(Modifier.padding(16.dp)) {
            Text("Sales trend \u00b7 last 6 months", fontWeight = FontWeight.Bold)
            Divider(Modifier.padding(vertical = 5.dp))
            BarChart(trend)
        }}
        val brandRows by produceState(initialValue = emptyList<Triple<String, Double, Double>>(), startMs, endMs) {
            value = withContext(Dispatchers.IO) { brandSales(ctx, startMs, endMs) }
        }
        if(brandRows.isNotEmpty()) Card(Modifier.padding(vertical = 5.dp)) { Column(Modifier.padding(16.dp)) {
            Text("Brand wise", fontWeight = FontWeight.Bold)
            Divider(Modifier.padding(vertical = 5.dp))
            brandRows.forEach { b ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), Arrangement.SpaceBetween) {
                    Text(b.first + "  (" + b.third.toInt() + " units)", fontSize = 10.sp)
                    Text("\u20b9" + fmt(b.second), fontSize = 10.sp)
                }
            }
            Divider(Modifier.padding(vertical = 4.dp))
            BarChart(brandRows.take(8).map { it.first.take(6) to it.second }, Color(0xFF1565C0))
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
    var showQuotes by rememberSaveable { mutableStateOf(false) }
    val (bills, loadingBills) = loadState(emptyList<InvoiceRec>(), reload) {
        withContext(Dispatchers.IO) { loadInvoices(ctx, false) }
    }
    val (quotes, _) = loadState(emptyList<InvoiceRec>(), reload) {
        withContext(Dispatchers.IO) { loadQuotes(ctx) }
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
    var cancelTarget by remember { mutableStateOf<Long?>(null) }
    var cancelReason by rememberSaveable { mutableStateOf("") }
    fun cancel(ms: Long, reason: String) {
        scope.launch {
            busy = true
            val ok = withContext(Dispatchers.IO) { cancelBill(ctx, ms, reason.ifBlank { "Cancelled" }) }
            busy = false
            reload++
            Toast.makeText(ctx, if(ok) "Bill cancelled and stock returned" else "Could not cancel this bill", Toast.LENGTH_LONG).show()
        }
    }
    fun convertQuote(ms: Long) {
        scope.launch {
            busy = true
            val f = withContext(Dispatchers.IO) { convertQuoteToInvoice(ctx, ms) }
            busy = false
            if(f != null) {
                Toast.makeText(ctx, "Quotation converted into an invoice", Toast.LENGTH_LONG).show()
                reload++
                openPdf(ctx, f)
            } else {
                Toast.makeText(ctx, "Could not convert this quotation", Toast.LENGTH_LONG).show()
            }
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
    if(cancelTarget != null) AlertDialog(
        onDismissRequest = { cancelTarget = null },
        title = { Text("Cancel this bill?") },
        text = { Column {
            Text("The bill stops counting in reports, search and the ledger, and its goods go back into stock. The record is kept for the audit trail.", fontSize = 11.sp)
            OutlinedTextField(cancelReason, {cancelReason=it}, label={Text("Reason (optional)")}, modifier=Modifier.fillMaxWidth(),
                keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
        } },
        confirmButton = { TextButton(onClick = { val t = cancelTarget; cancelTarget = null; if(t != null) cancel(t, cancelReason); cancelReason = "" }) { Text("CANCEL BILL") } },
        dismissButton = { TextButton(onClick = { cancelTarget = null; cancelReason = "" }) { Text("KEEP") } }
    )
    Column(Modifier.padding(16.dp)) {
        Text("FILES", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(query, {query=it}, label={Text("Search IMEI, phone, buyer or invoice no")},
            leadingIcon = { Icon(Icons.Default.Search, "Search") }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
        if(busy) LoadingRow("Building the PDF...")
        else if(loadingBills || loadingHits) LoadingRow("Reading bills...")
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            TextButton(onClick = { showQuotes = false }) { Text(if(showQuotes) "BILLS (${bills.size})" else "BILLS (${bills.size})", fontWeight = if(!showQuotes) FontWeight.Bold else FontWeight.Normal) }
            TextButton(onClick = { showQuotes = true }) { Text("QUOTES (${quotes.size})", fontWeight = if(showQuotes) FontWeight.Bold else FontWeight.Normal) }
        }
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
        } else if(showQuotes) {
            Text(if(quotes.isEmpty()) "No quotations saved yet - use the Quote screen on Entry" else "${quotes.size} quotation(s)", fontSize = 10.sp)
            LazyColumn {
                items(quotes) { rec ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                            Text(rec.invNo, fontWeight = FontWeight.Bold)
                            Text("\u20b9${fmt(rec.total)}", fontWeight = FontWeight.Bold)
                        }
                        Text(SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH).format(Date(rec.whenMs)) +
                             (if(rec.buyer.isBlank()) "" else "  \u00b7  " + rec.buyer), fontSize = 10.sp)
                        Row {
                            TextButton(onClick = { convertQuote(rec.whenMs) }) { Text("CONVERT TO INVOICE", fontSize = 10.sp, color = Color(0xFF2E7D32)) }
                            TextButton(onClick = { openBill(rec.whenMs) }) { Text("VIEW", fontSize = 10.sp) }
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
                            TextButton(onClick = { cancelTarget = rec.whenMs }) { Text("CANCEL", fontSize = 10.sp, color = Color.Red) }
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

@Composable fun SettingsScreen(hasPinSet: Boolean, onDataChanged: () -> Unit, onPinSave: (String)->Unit) {
    val focusManager = LocalFocusManager.current
    val ctx=LocalContext.current
    val prefs=remember{ctx.getSharedPreferences("penkhata_data",Context.MODE_PRIVATE)}
    var backupPw by rememberSaveable { mutableStateOf("") }
    var pendingEncrypted by remember { mutableStateOf<ByteArray?>(null) }
    var restorePw by rememberSaveable { mutableStateOf("") }
    var askPassword by remember { mutableStateOf(false) }
    // encrypted backups are raw AES-GCM bytes: declaring them as zip made the picker
    // append ".zip" and every file manager try (and fail) to extract them
    val backupEncLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        pickerInFlight = false
        if(uri != null) {
            try {
                val payload = backupPayload(ctx)
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(encryptBytes(payload, backupPw)) }
                Toast.makeText(ctx, "Encrypted backup saved - keep the password safe", Toast.LENGTH_LONG).show()
            } catch(e: Exception) {
                Toast.makeText(ctx, "Backup failed: " + (e.message ?: "unknown error"), Toast.LENGTH_LONG).show()
            }
        }
    }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        pickerInFlight = false
        if(uri != null) {
            try {
                val payload = backupPayload(ctx)
                ctx.contentResolver.openOutputStream(uri)?.use {
                    it.write(if(backupPw.isBlank()) payload else encryptBytes(payload, backupPw))
                }
                Toast.makeText(ctx, if(backupPw.isBlank()) "Backup saved" else "Encrypted backup saved - keep the password safe", Toast.LENGTH_LONG).show()
            } catch(e: Exception) {
                Toast.makeText(ctx, "Backup failed: " + (e.message ?: "unknown error"), Toast.LENGTH_LONG).show()
            }
        }
    }
    val scope = rememberCoroutineScope()
    var confirmMaster by remember { mutableStateOf(false) }
    var confirmAll by remember { mutableStateOf(false) }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pickerInFlight = false
        if(uri != null) {
            try {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                if(isEncryptedBackup(bytes)) {
                    pendingEncrypted = bytes; restorePw = ""; askPassword = true
                } else {
                    scope.launch {
                        // nothing in a restore may take the app down: a half-done restore
                        // is recoverable, a crash is not
                        try {
                            val snap = withContext(Dispatchers.IO) { autoBackup(ctx) }
                            val isZip = bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
                            val msg = withContext(Dispatchers.IO) {
                                if(isZip) restoreZip(ctx, ByteArrayInputStream(bytes)) else restoreJson(ctx, String(bytes, Charsets.UTF_8))
                            }
                            onDataChanged()
                            Toast.makeText(ctx, msg + (if(snap != null) " Previous data saved first." else ""), Toast.LENGTH_LONG).show()
                        } catch(t: Throwable) {
                            onDataChanged()
                            Toast.makeText(ctx, "Restore stopped: " + (t.message ?: t.javaClass.simpleName) + ". Settings shows the details.", Toast.LENGTH_LONG).show()
                            try { crashLogFile(ctx).writeText("Restore failed\n" + android.util.Log.getStackTraceString(t)) } catch(e: Exception) { }
                        }
                    }
                }
            } catch(e: Exception) {
                Toast.makeText(ctx, "Restore failed: " + (e.message ?: "unknown error"), Toast.LENGTH_LONG).show()
            }
        }
    }
    var sName by remember{mutableStateOf(prefs.getString("sName","")?:"")}; var sState by remember{mutableStateOf(prefs.getString("sState","21")?:"21")}; var sPrefix by remember{mutableStateOf(prefs.getString("sPrefix","INV")?:"INV")}; var sAddr1 by remember{mutableStateOf(prefs.getString("sAddr1","")?:(prefs.getString("sAddr","")?:"").lineSequence().firstOrNull()?.trim()?:"")}; var sAddr2 by remember{mutableStateOf(prefs.getString("sAddr2","")?:(prefs.getString("sAddr","")?:"").lineSequence().drop(1).joinToString(" ").trim())}; var sPhone by remember{mutableStateOf(prefs.getString("sPhone","")?:"")}; var sEmail by remember{mutableStateOf(prefs.getString("sEmail","")?:"")}; var sLandmark by remember{mutableStateOf(prefs.getString("sLandmark","")?:"")}; var sPincode by remember{mutableStateOf(prefs.getString("sPincode","")?:"")}; var sUpi by remember{mutableStateOf(prefs.getString("sUpi","")?:"")}; var sGst by remember{mutableStateOf(prefs.getString("sGst","")?:"")}; var sBank by remember{mutableStateOf(prefs.getString("sBank","")?:"")}; var sIfsc by remember{mutableStateOf(prefs.getString("sIfsc","")?:"")}; var sJuris by remember{mutableStateOf(prefs.getString("sJuris","")?:"")}; var defTax by remember{mutableStateOf(prefs.getString("defTax","18")?:"18")}; var pendingPerson by remember { mutableStateOf<Person?>(null) }; var editPerson by remember { mutableStateOf<Person?>(null) }; var editName by remember { mutableStateOf("") }; var editRole by remember { mutableStateOf("") }; var editPhone by remember { mutableStateOf("") }; var editBranch by remember { mutableStateOf<Branch?>(null) }; var editCode by remember { mutableStateOf("") }; var editBName by remember { mutableStateOf("") }; var editSeries by remember { mutableStateOf("") }; var editGstin by remember { mutableStateOf("") }; var editNext by remember { mutableStateOf("1") }; var rolePin by remember { mutableStateOf("") }; var newPerson by remember{mutableStateOf("")}; var newRole by remember{mutableStateOf("MANAGER")}; var newPin by remember{mutableStateOf("")}; Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())){        Text("SETTINGS", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        var settingsTab by rememberSaveable { mutableStateOf("business") }
        var backupUnlocked by remember { mutableStateOf(false) }
        var backupPin by remember { mutableStateOf("") }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), Arrangement.SpaceBetween) {
            listOf("business" to "Business", "team" to "Team", "security" to "Security", "backup" to "Backup").forEach { (id, label) ->
                Button(onClick = { settingsTab = id }, modifier = Modifier.weight(1f).padding(horizontal = 2.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if(settingsTab == id) Color(0xFF2E7D32) else Color.LightGray)) {
                    Text(label, fontSize = 9.sp, color = if(settingsTab == id) Color.White else Color.Black, maxLines = 1)
                }
            }
        }
if(settingsTab == "business") {
OutlinedTextField(sName,{sName=it},label={Text("Firm Name")},modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); OutlinedTextField(sState,{sState=it},label={Text("My State Code (GST, e.g. 21)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number, imeAction=ImeAction.Next),modifier=Modifier.fillMaxWidth(), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); OutlinedTextField(sPrefix,{sPrefix=it},label={Text("Invoice Prefix")},modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); OutlinedTextField(sAddr1,{sAddr1=it},label={Text("Address Line 1")},modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); OutlinedTextField(sAddr2,{sAddr2=it},label={Text("Address Line 2")},modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); OutlinedTextField(sPhone,{sPhone=it},label={Text("Phone")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Phone, imeAction=ImeAction.Next),modifier=Modifier.fillMaxWidth(), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); OutlinedTextField(sEmail,{sEmail=it},label={Text("Email")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email, imeAction=ImeAction.Next),modifier=Modifier.fillMaxWidth(), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); OutlinedTextField(sLandmark,{sLandmark=it},label={Text("Landmark")},modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); OutlinedTextField(sPincode,{sPincode=it},label={Text("Pincode")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number, imeAction=ImeAction.Next),modifier=Modifier.fillMaxWidth(), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); OutlinedTextField(sUpi,{sUpi=it},label={Text("UPI ID (for the payment QR)")},modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); OutlinedTextField(sGst,{sGst=it},label={Text("GSTIN")},modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); OutlinedTextField(sBank,{sBank=it},label={Text("Bank Name & Acc")},modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); OutlinedTextField(sIfsc,{sIfsc=it},label={Text("IFSC")},modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); OutlinedTextField(sJuris,{sJuris=it},label={Text("Jurisdiction")},modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); OutlinedTextField(defTax,{defTax=it},label={Text("Default Tax %")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number, imeAction=ImeAction.Next),modifier=Modifier.fillMaxWidth(), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); Text("PenKhata "+APP_VERSION, fontSize=10.sp, color=Color.Gray, modifier=Modifier.padding(top=8.dp)); val dbBills by produceState(initialValue = -1, ctx) { value = withContext(Dispatchers.IO) { countRows(ctx, "bills") } }; val dbStock by produceState(initialValue = -1, ctx) { value = withContext(Dispatchers.IO) { countRows(ctx, "stock") } }; Text("Database: " + (if(dbBills < 0) "reading..." else "$dbBills bills, $dbStock stock lines"), fontSize=10.sp, color=Color.Gray); Button(onClick={prefs.edit().putString("sName",sName).putString("sState",sState).putString("sPrefix",sPrefix).putString("sAddr1",sAddr1).putString("sAddr2",sAddr2).putString("sPhone",sPhone).putString("sEmail",sEmail).putString("sLandmark",sLandmark).putString("sPincode",sPincode).putString("sUpi",sUpi).putString("sGst",sGst).putString("sBank",sBank).putString("sIfsc",sIfsc).putString("sJuris",sJuris).putString("defTax",defTax).apply(); Toast.makeText(ctx,"Saved",Toast.LENGTH_SHORT).show()},modifier=Modifier.fillMaxWidth()){Text("SAVE")}; 
}
if(settingsTab == "team") {
Card(Modifier.padding(vertical=5.dp)){Column(Modifier.padding(10.dp)){
                val branches by produceState(initialValue = emptyList<Branch>(), ctx) { value = withContext(Dispatchers.IO) { loadBranches(ctx) } }
                val people by produceState(initialValue = emptyList<Person>(), ctx) { value = withContext(Dispatchers.IO) { loadPeople(ctx) } }
                Text("Outlet & Team", fontWeight=FontWeight.Bold)
                Divider(Modifier.padding(vertical=5.dp))
                Text("Acting as: " + activePerson(prefs) + " (" + activeRole(prefs) + ")", fontSize=11.sp, fontWeight=FontWeight.Bold)
                branches.forEach { b ->
                    Row(Modifier.fillMaxWidth().clickable { prefs.edit().putString("activeBranch", b.code).apply(); onDataChanged() }.padding(vertical=3.dp), verticalAlignment=Alignment.CenterVertically){
                        Text(if(b.code == activeBranchCode(prefs)) "\u2714 " else "   ", fontSize=11.sp)
                        Text(b.code + " \u00b7 " + b.name, fontSize=11.sp, modifier=Modifier.weight(1f), maxLines=1)
                        Text(b.series + " \u00b7 next " + String.format("%05d", b.nextSerial), fontSize=9.sp, color=Color.Gray)
                        Text("  \u270e", fontSize=13.sp, modifier=Modifier.clickable {
                            editBranch = b; editCode = b.code; editBName = b.name; editSeries = b.series; editGstin = b.gstin; editNext = b.nextSerial.toString()
                        })
                    }
                }
                Divider(Modifier.padding(vertical=4.dp))
                people.forEach { person ->
                    Row(Modifier.fillMaxWidth().clickable {
                        prefs.edit().putString("activePerson", person.name).putString("activeRole", person.role).apply(); onDataChanged()
                    }.padding(vertical=3.dp), verticalAlignment=Alignment.CenterVertically){
                        Text(if(person.name == activePerson(prefs)) "\u2714 " else "   ", fontSize=11.sp)
                        Text(person.name, fontSize=11.sp, modifier=Modifier.weight(1f), maxLines=1)
                        Text(person.role, fontSize=9.sp, color=if(person.role=="OWNER") Color(0xFF2E7D32) else Color.Gray)
                        Text("  \u270e", fontSize=13.sp, modifier=Modifier.clickable {
                            editPerson = person; editName = person.name; editRole = person.role; editPhone = person.phone
                        })
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row {
                    OutlinedTextField(newPerson, {newPerson=it}, label={Text("Add person")}, modifier=Modifier.weight(1f), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
                    Spacer(Modifier.width(4.dp))
                    OutlinedTextField(newRole, {newRole=it}, label={Text("Role")}, modifier=Modifier.weight(0.7f), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
                }
                Button(onClick = {
                    if(newPerson.isNotBlank()) {
                        val role = newRole.trim().uppercase().let { if(it in listOf("OWNER","MANAGER","STOCK","REP")) it else "REP" }
                        scope.launch {
                            withContext(Dispatchers.IO) { savePerson(ctx, Person(0, newPerson.trim(), role, activeBranchCode(prefs), "", true)) }
                            newPerson=""; newRole="MANAGER"; onDataChanged()
                        }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("ADD PERSON") }
                Text("OWNER: everything. MANAGER: billing, stock and this outlet's reports. STOCK: stock, purchases and audit. REP: billing and stock. Everyone can see stock in hand; only the OWNER can cancel a bill.", fontSize=9.sp, color=Color.Gray)
            }}
            
}
if(settingsTab == "security") {
val crashText by produceState(initialValue = "", ctx) { value = withContext(Dispatchers.IO) { readCrashLog(ctx) } }
            if(crashText.isNotBlank()) Card(Modifier.padding(vertical=5.dp)){Column(Modifier.padding(10.dp)){
                Text("Last error", fontWeight=FontWeight.Bold, color=Color.Red)
                Text(crashText.take(1200), fontSize=9.sp, color=Color.DarkGray)
                Row {
                    TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { clearCrashLog(ctx) }; onDataChanged() } }) { Text("CLEAR", fontSize=10.sp) }
                }
            }}
            OutlinedTextField(newPin,{newPin=it},label={Text(if(hasPinSet) "New PIN - blank then SAVE PIN removes the lock" else "Set Login PIN (optional)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword, imeAction=ImeAction.Next),modifier=Modifier.fillMaxWidth(), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); 
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Button(onClick = {
                    onPinSave(newPin)
                    newPin = ""
                    Toast.makeText(ctx, if(hasPinSet) "PIN saved" else "PIN set - the app will lock now", Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.weight(1f)) { Text("SAVE PIN") }
                Spacer(Modifier.width(6.dp))
                if(hasPinSet) OutlinedButton(onClick = {
                    onPinSave("")
                    newPin = ""
                    Toast.makeText(ctx, "Lock removed", Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.weight(1f)) { Text("REMOVE LOCK") }
            }
}
if(settingsTab == "backup") {
            if(hasPin(prefs) && !backupUnlocked) {
                Card(Modifier.padding(vertical = 8.dp)) { Column(Modifier.padding(16.dp)) {
                    Text("Locked", fontWeight = FontWeight.Bold)
                    Text("Backup, restore and reset can destroy data, so they sit behind the owner PIN.", fontSize = 10.sp, color = Color.Gray)
                    OutlinedTextField(backupPin, {backupPin=it}, label={Text("Owner PIN")}, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword), modifier=Modifier.fillMaxWidth())
                    Button(onClick = {
                        if(pinMatches(prefs, backupPin)) { backupUnlocked = true; backupPin = "" }
                        else Toast.makeText(ctx, "Wrong PIN", Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.fillMaxWidth()) { Text("UNLOCK") }
                }}
            } else {
Card(Modifier.padding(vertical=10.dp)){Column(Modifier.padding(10.dp)){ Text("Backup & Restore", fontWeight=FontWeight.Bold); Text("One file with your data, a readable XLSX copy and any stored PDFs. Add a password to encrypt it - there is no recovery if you forget it. The app PIN is never included.", fontSize=10.sp); OutlinedTextField(backupPw, {backupPw=it}, label={Text("Password (optional)")}, modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) })); Spacer(Modifier.height(8.dp)); Button(onClick={
                val name = "penkhata-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.ENGLISH).format(Date())
                pickerInFlight = true
                if(backupPw.isBlank()) backupLauncher.launch("$name.zip") else backupEncLauncher.launch("$name.pkh")
            }, modifier=Modifier.fillMaxWidth()){ Text("BACKUP DATA") }; Spacer(Modifier.height(6.dp)); Button(onClick={ pickerInFlight = true; restoreLauncher.launch(arrayOf("application/json","text/plain","*/*")) }, modifier=Modifier.fillMaxWidth()){ Text("RESTORE DATA") } }}; Card(Modifier.padding(vertical=10.dp)){Column(Modifier.padding(10.dp)){ Text("Reset", fontWeight=FontWeight.Bold); Text("Master reset clears bills, PDFs, customers, stock, ledger and expenses but keeps every setting on this screen. Total reset clears those as well and starts the app empty.", fontSize=10.sp); Spacer(Modifier.height(8.dp)); Button(onClick={ confirmMaster = true }, modifier=Modifier.fillMaxWidth(), colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFEF6C00))){ Text("MASTER RESET (KEEP SETTINGS)") }; Spacer(Modifier.height(6.dp)); Button(onClick={ confirmAll = true }, modifier=Modifier.fillMaxWidth(), colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFC62828))){ Text("TOTAL RESET (EVERYTHING)") } }};
            }
}
     if(editPerson != null) AlertDialog(
        onDismissRequest = { editPerson = null },
        title = { Text("Edit person") },
        text = { Column {
            OutlinedTextField(editName, {editName=it}, label={Text("Name")}, modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
            Row {
                OutlinedTextField(editRole, {editRole=it}, label={Text("Role")}, modifier=Modifier.weight(1f), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
                Spacer(Modifier.width(4.dp))
                OutlinedTextField(editPhone, {editPhone=it}, label={Text("Phone")}, modifier=Modifier.weight(1f), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
            }
            Text("Role must be OWNER, MANAGER or REP.", fontSize=9.sp, color=Color.Gray)
        } },
        confirmButton = { TextButton(onClick = {
            val p0 = editPerson
            if(p0 != null && editName.isNotBlank()) {
                val role = editRole.trim().uppercase().let { if(it in listOf("OWNER","MANAGER","STOCK","REP")) it else p0.role }
                val losingOwner = p0.role == "OWNER" && role != "OWNER" && ownerCount(ctx) <= 1
                if(losingOwner) {
                    Toast.makeText(ctx, "There must always be at least one OWNER", Toast.LENGTH_LONG).show()
                } else {
                    scope.launch {
                        withContext(Dispatchers.IO) { renamePerson(ctx, p0.id, editName.trim(), role, editPhone.trim()) }
                        if(activePerson(prefs) == p0.name) prefs.edit().putString("activePerson", editName.trim()).putString("activeRole", role).apply()
                        editPerson = null; onDataChanged()
                    }
                }
            }
        }) { Text("SAVE") } },
        dismissButton = { TextButton(onClick = {
            val p0 = editPerson
            if(p0 != null) {
                if(p0.role == "OWNER" && ownerCount(ctx) <= 1) {
                    Toast.makeText(ctx, "The last OWNER cannot be deleted", Toast.LENGTH_LONG).show()
                } else {
                    scope.launch {
                        withContext(Dispatchers.IO) { deletePerson(ctx, p0.id) }
                        if(activePerson(prefs) == p0.name) prefs.edit().putString("activePerson", "Owner").putString("activeRole", "OWNER").apply()
                        editPerson = null; onDataChanged()
                    }
                }
            }
        }) { Text("DELETE", color = Color.Red) } }
    )
    if(editBranch != null) AlertDialog(
        onDismissRequest = { editBranch = null },
        title = { Text("Edit outlet") },
        text = { Column {
            Row {
                OutlinedTextField(editCode, {editCode=it}, label={Text("Code")}, modifier=Modifier.weight(1f), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
                Spacer(Modifier.width(4.dp))
                OutlinedTextField(editSeries, {editSeries=it}, label={Text("Series")}, modifier=Modifier.weight(1f), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
            }
            OutlinedTextField(editBName, {editBName=it}, label={Text("Outlet name")}, modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
            OutlinedTextField(editGstin, {editGstin=it}, label={Text("GSTIN")}, modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
            OutlinedTextField(editNext, {editNext=it}, label={Text("Next invoice number")}, modifier=Modifier.fillMaxWidth(),
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number, imeAction=ImeAction.Done))
            val previewCode = editCode.trim().ifBlank { "CODE" }
            val previewSeries = editSeries.trim()
            val previewNo = String.format("%05d", editNext.toIntOrNull() ?: 1)
            Text("Next bill: " + (if(previewSeries.isBlank()) previewCode + "/" + fyLabel() + "/" + previewNo else previewCode + "/" + previewSeries + "/" + previewNo),
                fontSize=9.sp, color=Color(0xFF2E7D32))
        } },
        confirmButton = { TextButton(onClick = {
            val b0 = editBranch
            if(b0 != null && editCode.isNotBlank()) {
                scope.launch {
                    withContext(Dispatchers.IO) { saveBranch(ctx, b0.copy(code = editCode.trim(), name = editBName.trim(), series = editSeries.trim(), gstin = editGstin.trim(), nextSerial = editNext.toIntOrNull() ?: b0.nextSerial)) }
                    prefs.edit().putString("activeBranch", editCode.trim()).apply()
                    editBranch = null; onDataChanged()
                }
            }
        }) { Text("SAVE") } },
        dismissButton = { TextButton(onClick = { editBranch = null }) { Text("CLOSE") } }
    )
    if(pendingPerson != null) AlertDialog(
        onDismissRequest = { pendingPerson = null },
        title = { Text("Switch to " + (pendingPerson?.name ?: "") + "?") },
        text = { Column {
            Text("Enter the app PIN to act as " + (pendingPerson?.role ?: "") + ".", fontSize = 11.sp)
            OutlinedTextField(rolePin, {rolePin=it}, label={Text("PIN")}, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword, imeAction=ImeAction.Done),
                keyboardActions=KeyboardActions(onDone={ }),
                modifier=Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(onClick = {
            val pp = pendingPerson
            if(pp != null && pinMatches(prefs, rolePin)) {
                prefs.edit().putString("activePerson", pp.name).putString("activeRole", pp.role).apply()
                pendingPerson = null; rolePin = ""; onDataChanged()
            } else {
                Toast.makeText(ctx, "Wrong PIN", Toast.LENGTH_SHORT).show()
            }
        }) { Text("SWITCH") } },
        dismissButton = { TextButton(onClick = { pendingPerson = null; rolePin = "" }) { Text("CANCEL") } }
    )
    if(askPassword) AlertDialog(
        onDismissRequest = { askPassword = false; pendingEncrypted = null },
        title = { Text("Backup password") },
        text = {
            Column {
                Text("This backup is encrypted. Enter the password you set when making it.", fontSize = 11.sp)
                OutlinedTextField(restorePw, {restorePw=it}, label={Text("Password")}, modifier=Modifier.fillMaxWidth(), keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next), keyboardActions=KeyboardActions(onNext={ focusManager.moveFocus(FocusDirection.Down) }))
            }
        },
        confirmButton = { TextButton(onClick = {
            val data = pendingEncrypted
            if(data != null) {
                scope.launch {
                    val plain = withContext(Dispatchers.IO) { decryptBytes(data, restorePw) }
                    if(plain == null) {
                        Toast.makeText(ctx, "Wrong password", Toast.LENGTH_LONG).show()
                    } else {
                        askPassword = false; pendingEncrypted = null
                        try {
                            val snap = withContext(Dispatchers.IO) { autoBackup(ctx) }
                            val msg = withContext(Dispatchers.IO) {
                                if(plain.size > 2 && plain[0] == 'P'.code.toByte() && plain[1] == 'K'.code.toByte()) restoreZip(ctx, ByteArrayInputStream(plain))
                                else restoreJson(ctx, String(plain, Charsets.UTF_8))
                            }
                            onDataChanged()
                            Toast.makeText(ctx, msg + (if(snap != null) " Previous data saved first." else ""), Toast.LENGTH_LONG).show()
                        } catch(t: Throwable) {
                            onDataChanged()
                            Toast.makeText(ctx, "Restore stopped: " + (t.message ?: t.javaClass.simpleName) + ". Settings shows the details.", Toast.LENGTH_LONG).show()
                            try { crashLogFile(ctx).writeText("Restore failed\n" + android.util.Log.getStackTraceString(t)) } catch(e: Exception) { }
                        }
                    }
                }
            }
        }) { Text("UNLOCK") } },
        dismissButton = { TextButton(onClick = { askPassword = false; pendingEncrypted = null }) { Text("CANCEL") } }
    )
    if(confirmMaster) AlertDialog(onDismissRequest={ confirmMaster = false }, title={ Text("Master reset?") }, text={ Text("Deletes every bill, PDF, customer, stock line, ledger entry and expense. Your outlet details and PIN stay.") }, confirmButton={ TextButton(onClick={ scope.launch { val n = withContext(Dispatchers.IO){ wipeTransactions(ctx) }; confirmMaster = false; onDataChanged(); Toast.makeText(ctx, "Cleared $n files.", Toast.LENGTH_LONG).show() } }){ Text("DELETE") } }, dismissButton={ TextButton(onClick={ confirmMaster = false }){ Text("CANCEL") } }); if(confirmAll) AlertDialog(onDismissRequest={ confirmAll = false }, title={ Text("Total reset?") }, text={ Text("Deletes everything: bills, PDFs, customers, stock, ledger, expenses, all settings and the PIN. The app goes back to a fresh install.") }, confirmButton={ TextButton(onClick={ scope.launch { val n = withContext(Dispatchers.IO){ wipeEverything(ctx) }; confirmAll = false; onDataChanged(); Toast.makeText(ctx, "Cleared $n files and all settings. Restart the app.", Toast.LENGTH_LONG).show() } }){ Text("DELETE EVERYTHING") } }, dismissButton={ TextButton(onClick={ confirmAll = false }){ Text("CANCEL") } }); Spacer(Modifier.height(60.dp))} }

// --- LOGIC ---
data class InvoiceRec(val invNo: String, val date: String, val whenMs: Long, val buyer: String, val buyerGst: String,
                      val total: Double, val taxable: Double, val cgst: Double, val sgst: Double, val igst: Double,
                      val tax: Double, val cogs: Double, val profit: Double, val interState: Boolean,
                      val paid: Double, val balance: Double,
                      val rates: Map<Double, Pair<Double, Double>>)
data class Expense(val id: Long, val ms: Long, val note: String, val amount: Double)

// Backups made before 3.6.2 lost the tax columns. Where a bill has lines but no tax,
// rebuild the aggregates from the lines so restored data is not shown as zero.
fun repairAggregates(ctx: Context): Int {
    var fixed = 0
    try {
        val w = db(ctx).writableDatabase
        val todo = mutableListOf<Pair<Long, Pair<Double, Double>>>()
        w.rawQuery("SELECT ms,lines,tax,total FROM bills", null).use { c ->
            while(c.moveToNext()) {
                val ms = c.getLong(0); val linesJson = c.getString(1) ?: "[]"
                if(c.getDouble(2) > 0.004 || c.getDouble(3) <= 0.004) continue
                var taxable = 0.0; var tax = 0.0
                try {
                    val arr = JSONArray(linesJson)
                    for(i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        taxable += o.optDouble("taxable", 0.0); tax += o.optDouble("tax", 0.0)
                    }
                } catch(e: Exception) { }
                if(tax > 0.004) todo.add(ms to (taxable to tax))
            }
        }
        for((ms, v) in todo) {
            val cv = android.content.ContentValues()
            cv.put("taxable", v.first); cv.put("tax", v.second)
            w.update("bills", cv, "ms=?", arrayOf(ms.toString()))
            w.rawQuery("SELECT bstate,seller FROM bills WHERE ms=?", arrayOf(ms.toString())).use { c ->
                if(c.moveToFirst()) {
                    val bstate = c.getString(0) ?: ""
                    val sstate = try { JSONObject(c.getString(1) ?: "{}").optString("state") } catch(e: Exception) { "" }
                    val inter = bstate.isNotBlank() && sstate.isNotBlank() && bstate.trim() != sstate.trim()
                    val cv2 = android.content.ContentValues()
                    if(inter) { cv2.put("igst", v.second); cv2.put("cgst", 0.0); cv2.put("sgst", 0.0); cv2.put("interState", 1) }
                    else { cv2.put("igst", 0.0); cv2.put("cgst", v.second/2); cv2.put("sgst", v.second/2); cv2.put("interState", 0) }
                    w.update("bills", cv2, "ms=?", arrayOf(ms.toString()))
                }
            }
            fixed++
        }
    } catch(e: Exception) { }
    return fixed
}
fun loadInvoices(ctx: Context, includeQuotes: Boolean = false): List<InvoiceRec> {
    val out = mutableListOf<InvoiceRec>()
    val where = if(includeQuotes) " WHERE cancelled=0" else " WHERE quote=0 AND cancelled=0"
    try {
        db(ctx).readableDatabase.rawQuery("SELECT inv,date,ms,buyer,bgst,total,taxable,cgst,sgst,igst,tax,cogs,profit,interState,paid,balance,lines FROM bills$where ORDER BY ms DESC", null).use { c ->
            while(c.moveToNext()) {
                val rates = mutableMapOf<Double, Pair<Double, Double>>()
                try {
                    val arr = JSONArray(c.getString(16) ?: "[]")
                    for(i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val r = o.optDouble("taxRate", 0.0)
                        val prev = rates[r] ?: (0.0 to 0.0)
                        rates[r] = (prev.first + o.optDouble("taxable", 0.0)) to (prev.second + o.optDouble("tax", 0.0))
                    }
                } catch(e: Exception) { }
                out.add(InvoiceRec(c.getString(0) ?: "", c.getString(1) ?: "", c.getLong(2), c.getString(3) ?: "", c.getString(4) ?: "",
                    c.getDouble(5), c.getDouble(6), c.getDouble(7), c.getDouble(8), c.getDouble(9), c.getDouble(10),
                    c.getDouble(11), c.getDouble(12), c.getInt(13) != 0, c.getDouble(14), c.getDouble(15), rates))
            }
        }
    } catch(e: Exception) { }
    return out
}
// Every buyer seen on a saved bill, newest first, for one-tap fill on the entry screen.
fun loadQuotes(ctx: Context): List<InvoiceRec> {
    val out = mutableListOf<InvoiceRec>()
    try {
        db(ctx).readableDatabase.rawQuery("SELECT inv,date,ms,buyer,bgst,total,taxable,cgst,sgst,igst,tax,cogs,profit,interState,paid,balance,lines FROM bills WHERE quote=1 AND cancelled=0 ORDER BY ms DESC", null).use { c ->
            while(c.moveToNext()) {
                out.add(InvoiceRec(c.getString(0) ?: "", c.getString(1) ?: "", c.getLong(2), c.getString(3) ?: "", c.getString(4) ?: "",
                    c.getDouble(5), c.getDouble(6), c.getDouble(7), c.getDouble(8), c.getDouble(9), c.getDouble(10),
                    c.getDouble(11), c.getDouble(12), c.getInt(13) != 0, c.getDouble(14), c.getDouble(15), emptyMap()))
            }
        }
    } catch(e: Exception) { }
    return out
}

// Turns a saved quotation into a real bill: new number, today's date, stock and
// invoice records updated exactly as if it had been typed in.
fun convertQuoteToInvoice(ctx: Context, quoteMs: Long): File? {
    val o = billJson(ctx, quoteMs) ?: return null
    val lines = mutableListOf<InvItem>()
    try {
        val arr = o.optJSONArray("lines") ?: JSONArray()
        for(i in 0 until arr.length()) {
            val l = arr.optJSONObject(i) ?: continue
            lines.add(InvItem(l.optString("desc"), l.optString("serial"), l.optString("hsn"),
                l.optDouble("qty", 1.0), l.optDouble("rate", 0.0), l.optString("unit"),
                l.optDouble("taxRate", 18.0), l.optDouble("discount", 0.0)))
        }
    } catch(e: Exception) { }
    if(lines.isEmpty()) return null
    val sel = o.optJSONObject("seller") ?: JSONObject()
    val seller = SellerInfo(sel.optString("name"), sel.optString("addr"), sel.optString("phone"), sel.optString("email"),
        sel.optString("landmark"), sel.optString("pincode"), sel.optString("gst"), sel.optString("bank"),
        sel.optString("ifsc"), sel.optString("juris"), sel.optString("upi"), sel.optString("state"))
    val buyer = BuyerInfo(o.optString("buyer"), o.optString("baddr1"), o.optString("baddr2"), o.optString("blmk"),
        o.optString("bpin"), o.optString("bphone"), o.optString("bgst"), o.optString("bstate"))
    val bd = o.optDouble("bd", 0.0); val exch = o.optDouble("exch", 0.0)
    val cash = o.optDouble("cash", 0.0); val upi = o.optDouble("upi", 0.0); val card = o.optDouble("card", 0.0)
    val calc = computeBill(lines, bd, exch, cash, upi, card, seller.state, buyer.state, discountDivisor(ctx.getSharedPreferences("penkhata_data", Context.MODE_PRIVATE)))
    val prefs = ctx.getSharedPreferences("penkhata_data", Context.MODE_PRIVATE)
    val invNo = nextInvoiceNo(ctx, prefs)
    val dateStr = SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH).format(Date())
    val ms = System.currentTimeMillis()
    saveInvoiceData(ctx, ms, false, invNo, dateStr, o.optString("payMode"), o.optString("delNote"), buyer, seller,
        bd, exch, cash, upi, card, o.optString("finance"), calc, lines)
    deductStock(ctx, lines)
    bumpInvoiceNo(ctx, prefs)
    return createPdf(ctx, false, invNo, dateStr, o.optString("payMode"), o.optString("delNote"),
        seller.name, seller.addr, seller.phone, seller.email, seller.landmark, seller.pincode, seller.gst,
        seller.bank, seller.ifsc, seller.juris, buyer.name, buyer.addr1, buyer.addr2, buyer.landmark,
        buyer.pincode, buyer.phone, buyer.gst, buyer.state, lines, calc, o.optString("finance"), ms,
        seller.upi, billOutDir(ctx))
}

// Cancelling a bill: the record stays (for the audit trail) but it stops counting
// in reports, search and the ledger, and its goods go back into stock.
fun restoreStockForBill(ctx: Context, lines: List<InvItem>) {
    if(lines.isEmpty()) return
    val list = loadStock(ctx).toMutableList()
    var changed = false
    for(i in lines) {
        val idx = list.indexOfFirst { it.model.isNotBlank() && i.desc.lowercase().contains(it.model.lowercase()) }
        if(idx >= 0) {
            val e = list[idx]
            val imeis = e.imeiList().toMutableList()
            if(i.serial.isNotBlank() && !imeis.contains(i.serial)) imeis.add(i.serial)
            list[idx] = e.copy(qty = e.qty + i.qty.toInt(), imei = imeis.joinToString("\n"))
            changed = true
        }
    }
    if(changed) saveStock(ctx, list)
}
fun cancelBill(ctx: Context, ms: Long, reason: String): Boolean {
    return try {
        val o = billJson(ctx, ms) ?: return false
        val lines = mutableListOf<InvItem>()
        val arr = o.optJSONArray("lines") ?: JSONArray()
        for(i in 0 until arr.length()) {
            val l = arr.optJSONObject(i) ?: continue
            lines.add(InvItem(l.optString("desc"), l.optString("serial"), l.optString("hsn"),
                l.optDouble("qty", 1.0), l.optDouble("rate", 0.0), l.optString("unit"),
                l.optDouble("taxRate", 18.0), l.optDouble("discount", 0.0)))
        }
        val cv = android.content.ContentValues()
        cv.put("cancelled", 1); cv.put("cancelReason", reason)
        val n = db(ctx).writableDatabase.update("bills", cv, "ms=?", arrayOf(ms.toString()))
        if(n > 0) { restoreStockForBill(ctx, lines); true } else false
    } catch(e: Exception) { false }
}
// ---------------------------------------------------------------- customers
// One phone number, one customer. The name registered first is kept forever:
// overwriting it would rewrite history, and a bill from 2026 must still read the
// same in 2046.
fun findCustomerByPhone(ctx: Context, phone: String): BuyerInfo? {
    val p = phone.trim()
    if(p.length < 6) return null
    return try {
        db(ctx).readableDatabase.rawQuery("SELECT name,addr1,addr2,lmk,pin,gst,state FROM customers WHERE phone=?", arrayOf(p)).use { c ->
            if(c.moveToFirst()) BuyerInfo(c.getString(0) ?: "", c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "",
                c.getString(4) ?: "", p, c.getString(5) ?: "", c.getString(6) ?: "") else null
        }
    } catch(e: Exception) { null }
}
// Record a sale against a phone number. Existing names are never replaced.
fun upsertCustomer(ctx: Context, b: BuyerInfo, ms: Long) {
    if(b.phone.trim().length < 6) return
    try {
        val w = db(ctx).writableDatabase
        val cv = android.content.ContentValues()
        cv.put("phone", b.phone.trim()); cv.put("name", b.name.trim())
        cv.put("addr1", b.addr1); cv.put("addr2", b.addr2); cv.put("lmk", b.landmark)
        cv.put("pin", b.pincode); cv.put("gst", b.gst); cv.put("state", b.state)
        cv.put("firstMs", ms); cv.put("lastMs", ms); cv.put("bills", 1)
        w.insertWithOnConflict("customers", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
        w.execSQL("UPDATE customers SET lastMs=?, bills=bills+1 WHERE phone=?", arrayOf(ms, b.phone.trim()))
    } catch(e: Exception) { }
}
// Indexed search: phone by prefix, name anywhere. Capped, because a picker only
// ever needs a screenful.
fun searchCustomers(ctx: Context, query: String, limit: Int = 20): List<BuyerInfo> {
    val q = query.trim()
    if(q.isBlank()) return listOf()
    val out = mutableListOf<BuyerInfo>()
    try {
        db(ctx).readableDatabase.rawQuery(
            "SELECT name,addr1,addr2,lmk,pin,phone,gst,state FROM customers WHERE phone LIKE ? OR name LIKE ? ORDER BY lastMs DESC LIMIT ?",
            arrayOf(q + "%", "%" + q + "%", limit.toString())).use { c ->
            while(c.moveToNext()) out.add(BuyerInfo(c.getString(0) ?: "", c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "",
                c.getString(4) ?: "", c.getString(5) ?: "", c.getString(6) ?: "", c.getString(7) ?: ""))
        }
    } catch(e: Exception) { }
    return out
}
fun customerCount(ctx: Context): Int = try {
    db(ctx).readableDatabase.rawQuery("SELECT COUNT(*) FROM customers", null).use { if(it.moveToFirst()) it.getInt(0) else 0 }
} catch(e: Exception) { 0 }
// Build the master once from the bills already in the database.
fun migrateCustomers(ctx: Context) {
    try {
        if(customerCount(ctx) > 0) return
        db(ctx).readableDatabase.rawQuery("SELECT buyer,baddr1,baddr2,blmk,bpin,bphone,bgst,bstate,ms FROM bills WHERE bphone<>'' ORDER BY ms", null).use { c ->
            while(c.moveToNext()) {
                upsertCustomer(ctx, BuyerInfo(c.getString(0) ?: "", c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "",
                    c.getString(4) ?: "", c.getString(5) ?: "", c.getString(6) ?: "", c.getString(7) ?: ""), c.getLong(8))
            }
        }
    } catch(e: Exception) { }
}

fun loadCustomers(ctx: Context): List<BuyerInfo> {
    val out = mutableListOf<BuyerInfo>()
    try {
        db(ctx).readableDatabase.rawQuery("SELECT buyer,baddr1,baddr2,blmk,bpin,bphone,bgst,bstate FROM bills WHERE buyer<>'' AND cancelled=0 ORDER BY ms DESC", null).use { c ->
            while(c.moveToNext()) {
                val b = BuyerInfo(c.getString(0) ?: "", c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "",
                    c.getString(4) ?: "", c.getString(5) ?: "", c.getString(6) ?: "", c.getString(7) ?: "")
                if(out.none { it.name.equals(b.name, true) && it.phone == b.phone }) out.add(b)
            }
        }
    } catch(e: Exception) { }
    return out
}

data class BillHit(val rec: InvoiceRec, val pdf: String, val serial: String)
// Searches IMEI, phone, buyer name and invoice number. Bills saved before this build
// carry no phone or serial, so they only match on name and invoice number.
fun searchBills(ctx: Context, query: String): List<BillHit> {
    val q = query.trim()
    if(q.length < 2) return emptyList()
    val like = "%" + q + "%"
    val hits = mutableListOf<BillHit>()
    try {
        db(ctx).readableDatabase.rawQuery(
            "SELECT ms,inv,buyer,total,lines FROM bills WHERE quote=0 AND cancelled=0 AND (inv LIKE ? OR buyer LIKE ? OR bphone LIKE ? OR lines LIKE ?) ORDER BY ms DESC",
            arrayOf(like, like, like, like)).use { c ->
            while(c.moveToNext()) {
                val ms = c.getLong(0)
                var serial = ""
                try {
                    val arr = JSONArray(c.getString(4) ?: "[]")
                    for(i in 0 until arr.length()) {
                        val sv = arr.optJSONObject(i)?.optString("serial") ?: ""
                        if(sv.isNotBlank() && sv.lowercase().contains(q.lowercase())) { serial = sv; break }
                    }
                } catch(e: Exception) { }
                val rec = InvoiceRec(c.getString(1) ?: "", "", ms, c.getString(2) ?: "", "", c.getDouble(3),
                    0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, false, 0.0, 0.0, emptyMap())
                hits.add(BillHit(rec, "", serial))
            }
        }
    } catch(e: Exception) { }
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
fun billJson(ctx: Context, ms: Long): JSONObject? {
    return try {
        db(ctx).readableDatabase.rawQuery(
            "SELECT quote,inv,date,payMode,delNote,buyer,baddr1,baddr2,blmk,bpin,bphone,bgst,bstate,bd,exch,cash,upi,card,finance,seller,lines,total,taxable,cgst,sgst,igst,tax,cogs,profit,paid,balance,interState,cancelled,cancelReason FROM bills WHERE ms=?",
            arrayOf(ms.toString())).use { c ->
            if(!c.moveToFirst()) return null
            val j = JSONObject()
            j.put("ms", ms); j.put("quote", c.getInt(0) != 0); j.put("inv", c.getString(1) ?: "")
            j.put("date", c.getString(2) ?: ""); j.put("payMode", c.getString(3) ?: ""); j.put("delNote", c.getString(4) ?: "")
            j.put("buyer", c.getString(5) ?: ""); j.put("baddr1", c.getString(6) ?: ""); j.put("baddr2", c.getString(7) ?: "")
            j.put("blmk", c.getString(8) ?: ""); j.put("bpin", c.getString(9) ?: ""); j.put("bphone", c.getString(10) ?: "")
            j.put("bgst", c.getString(11) ?: ""); j.put("bstate", c.getString(12) ?: "")
            j.put("bd", c.getDouble(13)); j.put("exch", c.getDouble(14)); j.put("cash", c.getDouble(15))
            j.put("upi", c.getDouble(16)); j.put("card", c.getDouble(17)); j.put("finance", c.getString(18) ?: "")
            j.put("seller", try { JSONObject(c.getString(19) ?: "{}") } catch(e: Exception) { JSONObject() })
            j.put("lines", try { JSONArray(c.getString(20) ?: "[]") } catch(e: Exception) { JSONArray() })
            j.put("total", c.getDouble(21))
            // the accounting columns: without these a backup restored to zeros
            j.put("taxable", c.getDouble(22)); j.put("cgst", c.getDouble(23)); j.put("sgst", c.getDouble(24))
            j.put("igst", c.getDouble(25)); j.put("tax", c.getDouble(26)); j.put("cogs", c.getDouble(27))
            j.put("profit", c.getDouble(28)); j.put("paid", c.getDouble(29)); j.put("balance", c.getDouble(30))
            j.put("interState", c.getInt(31) != 0)
            j.put("cancelled", c.getInt(32) != 0); j.put("cancelReason", c.getString(33) ?: "")
            j
        }
    } catch(e: Exception) { null }
}

fun renderBill(ctx: Context, ms: Long): File? {
    val o = billJson(ctx, ms) ?: return null
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
        o.optDouble("cash", 0.0), o.optDouble("upi", 0.0), o.optDouble("card", 0.0), sellerState, o.optString("bstate"),
        discountDivisor(ctx.getSharedPreferences("penkhata_data", Context.MODE_PRIVATE)))
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
    try {
        db(ctx).readableDatabase.rawQuery("SELECT id,ms,note,amount FROM expenses ORDER BY ms", null).use { c ->
            while(c.moveToNext()) out.add(Expense(c.getLong(0), c.getLong(1), c.getString(2) ?: "", c.getDouble(3)))
        }
    } catch(e: Exception) { }
    return out
}
fun saveExpense(ctx: Context, note: String, amount: Double) {
    val ms = System.currentTimeMillis()
    val cv = android.content.ContentValues()
    cv.put("id", ms); cv.put("ms", ms); cv.put("note", note); cv.put("amount", amount)
    db(ctx).writableDatabase.insertWithOnConflict("expenses", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
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
// Days since the oldest thing this party still owes for: an unpaid bill or a khata debit.
fun partyAgeDays(entries: List<LedgerEntry>, invoices: List<InvoiceRec>, party: String, nowMs: Long): Long {
    val mine = entries.filter { it.party.trim().equals(party, true) && it.type == "DEBIT" }
    val bills = invoices.filter { it.buyer.trim().equals(party, true) && it.balance > 0.004 }
    val oldest = (mine.map { it.id } + bills.map { it.whenMs }).filter { it > 0L }.minOrNull() ?: return 0L
    return ((nowMs - oldest) / (24L * 60L * 60L * 1000L)).coerceAtLeast(0L)
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
class PenDb(ctx: Context) : android.database.sqlite.SQLiteOpenHelper(ctx, "penkhata.db", null, 3) {
    override fun onCreate(db: android.database.sqlite.SQLiteDatabase) {
        db.execSQL("""CREATE TABLE bills(ms INTEGER PRIMARY KEY, quote INTEGER, inv TEXT, date TEXT, payMode TEXT, delNote TEXT,
            buyer TEXT, bgst TEXT, baddr1 TEXT, baddr2 TEXT, blmk TEXT, bpin TEXT, bphone TEXT, bstate TEXT,
            total REAL, taxable REAL, cgst REAL, sgst REAL, igst REAL, tax REAL, interState INTEGER,
            cogs REAL, profit REAL, paid REAL, balance REAL, bd REAL, exch REAL, cash REAL, upi REAL, card REAL,
            finance TEXT, seller TEXT, lines TEXT, cancelled INTEGER DEFAULT 0, cancelReason TEXT DEFAULT '',
            branch TEXT DEFAULT '', series TEXT DEFAULT '', salesman TEXT DEFAULT '', deliveryDate TEXT DEFAULT '')""")
        db.execSQL("""CREATE TABLE branches(code TEXT PRIMARY KEY, name TEXT, address TEXT, pincode TEXT, gstin TEXT,
            phone TEXT, state TEXT, series TEXT, nextSerial INTEGER DEFAULT 1)""")
        db.execSQL("""CREATE TABLE people(id INTEGER PRIMARY KEY, name TEXT, role TEXT, branch TEXT, phone TEXT, active INTEGER DEFAULT 1)""")
        db.execSQL("""CREATE TABLE brands(id INTEGER PRIMARY KEY, name TEXT UNIQUE)""")
        db.execSQL("""CREATE TABLE categories(id INTEGER PRIMARY KEY, name TEXT UNIQUE)""")
        db.execSQL("""CREATE TABLE products(id INTEGER PRIMARY KEY, brand TEXT, category TEXT, model TEXT, hsn TEXT,
            gstRate REAL DEFAULT 18, mrp REAL DEFAULT 0, warrantyMonths INTEGER DEFAULT 12, serialised INTEGER DEFAULT 1)""")
        db.execSQL("""CREATE TABLE distributors(id INTEGER PRIMARY KEY, name TEXT, gstin TEXT, phone TEXT, address TEXT,
            creditLimit REAL DEFAULT 0, creditDays INTEGER DEFAULT 0, brands TEXT DEFAULT '', active INTEGER DEFAULT 1)""")
        db.execSQL("CREATE INDEX idx_bills_inv ON bills(inv)")
        db.execSQL("CREATE INDEX idx_bills_ms ON bills(ms)")
        db.execSQL("CREATE INDEX idx_bills_buyer ON bills(buyer)")
        db.execSQL("CREATE INDEX idx_bills_phone ON bills(bphone)")
        db.execSQL("""CREATE TABLE ledger(id INTEGER PRIMARY KEY, date TEXT, party TEXT, type TEXT, amt REAL, descr TEXT)""")
        db.execSQL("CREATE INDEX idx_ledger_party ON ledger(party)")
        db.execSQL("""CREATE TABLE stock(id INTEGER PRIMARY KEY, model TEXT, imei TEXT, hsn TEXT, cost REAL, mrp REAL, qty INTEGER, supplier TEXT, addedOn TEXT, brand TEXT DEFAULT '', cls TEXT DEFAULT '')""")
        db.execSQL("CREATE INDEX idx_stock_model ON stock(model)")
        db.execSQL("""CREATE TABLE expenses(id INTEGER PRIMARY KEY, ms INTEGER, note TEXT, amount REAL)""")
        db.execSQL("""CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)""")
    }
    override fun onUpgrade(db: android.database.sqlite.SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v2: a bill can be cancelled instead of deleted, so the record survives
        if(oldVersion < 2) {
            db.execSQL("ALTER TABLE bills ADD COLUMN cancelled INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE bills ADD COLUMN cancelReason TEXT DEFAULT ''")
        }
        // v3: outlets, people, and attribution on every bill
        if(oldVersion < 3) {
            db.execSQL("ALTER TABLE bills ADD COLUMN branch TEXT DEFAULT ''")
            db.execSQL("ALTER TABLE bills ADD COLUMN series TEXT DEFAULT ''")
            db.execSQL("ALTER TABLE bills ADD COLUMN salesman TEXT DEFAULT ''")
            db.execSQL("ALTER TABLE bills ADD COLUMN deliveryDate TEXT DEFAULT ''")
            db.execSQL("ALTER TABLE stock ADD COLUMN brand TEXT DEFAULT ''")
            db.execSQL("ALTER TABLE stock ADD COLUMN cls TEXT DEFAULT ''")
            db.execSQL("CREATE TABLE IF NOT EXISTS branches(code TEXT PRIMARY KEY, name TEXT, address TEXT, pincode TEXT, gstin TEXT, phone TEXT, state TEXT, series TEXT, nextSerial INTEGER DEFAULT 1)")
            db.execSQL("CREATE TABLE IF NOT EXISTS people(id INTEGER PRIMARY KEY, name TEXT, role TEXT, branch TEXT, phone TEXT, active INTEGER DEFAULT 1)")
        }
    }
    // Columns added in later versions are also checked on open: an install that created
    // its tables before those columns existed would otherwise fail its queries silently.
    private fun healColumns(db: android.database.sqlite.SQLiteDatabase, table: String, columns: List<Pair<String, String>>) {
        try {
            val have = mutableSetOf<String>()
            db.rawQuery("PRAGMA table_info($table)", null).use { c ->
                while(c.moveToNext()) have.add(c.getString(1) ?: "")
            }
            for((name, type) in columns) {
                if(!have.contains(name)) db.execSQL("ALTER TABLE $table ADD COLUMN $name $type")
            }
        } catch(e: Exception) { }
    }
    override fun onOpen(db: android.database.sqlite.SQLiteDatabase) {
        super.onOpen(db)
        healColumns(db, "bills", listOf(
            "cancelled" to "INTEGER DEFAULT 0", "cancelReason" to "TEXT DEFAULT ''",
            "branch" to "TEXT DEFAULT ''", "series" to "TEXT DEFAULT ''",
            "salesman" to "TEXT DEFAULT ''", "deliveryDate" to "TEXT DEFAULT ''"))
        healColumns(db, "stock", listOf("brand" to "TEXT DEFAULT ''", "cls" to "TEXT DEFAULT ''"))
        healColumns(db, "skus", listOf("modelId" to "INTEGER DEFAULT 0"))
        try { db.execSQL("CREATE TABLE IF NOT EXISTS models(id INTEGER PRIMARY KEY AUTOINCREMENT, brand TEXT, category TEXT, name TEXT, UNIQUE(brand, name))") } catch(e: Exception) { }
        try {
            db.execSQL("""CREATE TABLE IF NOT EXISTS purchases(ms INTEGER PRIMARY KEY, distributor TEXT, invoiceNo TEXT, date TEXT, branch TEXT,
                taxable REAL DEFAULT 0, gst REAL DEFAULT 0, total REAL DEFAULT 0, creditDays INTEGER DEFAULT 0, note TEXT DEFAULT '')""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS purchase_lines(ms INTEGER, model TEXT, brand TEXT, category TEXT, hsn TEXT,
                qty INTEGER DEFAULT 0, cost REAL DEFAULT 0, gstRate REAL DEFAULT 18, serials TEXT DEFAULT '')""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS grn(id INTEGER PRIMARY KEY AUTOINCREMENT, no TEXT, distributor TEXT,
                brand TEXT, date TEXT, docType TEXT, paperTotal REAL DEFAULT 0, taxable REAL DEFAULT 0, gst REAL DEFAULT 0,
                total REAL DEFAULT 0, creditDays INTEGER DEFAULT 30, status TEXT DEFAULT 'PENDING', branch TEXT,
                createdAt INTEGER DEFAULT 0)""")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_grn_no ON grn(distributor, brand, no)")
            db.execSQL("""CREATE TABLE IF NOT EXISTS grn_lines(id INTEGER PRIMARY KEY AUTOINCREMENT, grnId INTEGER,
                sku TEXT, model TEXT, variant TEXT, category TEXT, hsn TEXT, gstRate REAL DEFAULT 18,
                units INTEGER DEFAULT 0, price REAL DEFAULT 0, taxable REAL DEFAULT 0, identType TEXT DEFAULT 'NONE')""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS units(id INTEGER PRIMARY KEY AUTOINCREMENT, grnId INTEGER,
                lineId INTEGER, sku TEXT, serial1 TEXT, serial2 TEXT, cost REAL DEFAULT 0, dateIn TEXT,
                status TEXT DEFAULT 'PENDING', billMs INTEGER DEFAULT 0)""")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_units_serial ON units(serial1)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_units_grn ON units(grnId)")
            db.execSQL("""CREATE TABLE IF NOT EXISTS skus(sku TEXT PRIMARY KEY, brand TEXT, category TEXT, model TEXT,
                variant TEXT, hsn TEXT, gstRate REAL DEFAULT 18, mrp REAL DEFAULT 0, identType TEXT DEFAULT 'SERIAL',
                modelId INTEGER DEFAULT 0)""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS dist_brands(distributor TEXT, brand TEXT, categories TEXT,
                PRIMARY KEY(distributor, brand))""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS customers(phone TEXT PRIMARY KEY, name TEXT, addr1 TEXT, addr2 TEXT,
                lmk TEXT, pin TEXT, gst TEXT, state TEXT, firstMs INTEGER DEFAULT 0, lastMs INTEGER DEFAULT 0, bills INTEGER DEFAULT 0)""")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_cust_name ON customers(name)")
            db.execSQL("""CREATE TABLE IF NOT EXISTS audits(ms INTEGER PRIMARY KEY, date TEXT, branch TEXT, countedBy TEXT,
                note TEXT DEFAULT '', bookUnits INTEGER DEFAULT 0, countedUnits INTEGER DEFAULT 0,
                varianceUnits INTEGER DEFAULT 0, bookValue REAL DEFAULT 0, varianceValue REAL DEFAULT 0)""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS audit_lines(ms INTEGER, stockId INTEGER, model TEXT, brand TEXT,
                bookQty INTEGER DEFAULT 0, countedQty INTEGER DEFAULT 0, cost REAL DEFAULT 0, missingSerials TEXT DEFAULT '')""")
        } catch(e: Exception) { }
    }
    override fun onConfigure(db: android.database.sqlite.SQLiteDatabase) {
        try {
            db.enableWriteAheadLogging()
            db.rawQuery("PRAGMA busy_timeout=5000", null).use { it.moveToFirst() }
        } catch(e: Exception) { }
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }
}

// One database helper per process. Creating a helper per call meant several
// connections: a restore held the write lock on one while a screen read on
// another, and SQLite answered SQLITE_BUSY (code 5) - which crashed the restore.
private var dbInstance: PenDb? = null
fun db(ctx: Context): PenDb {
    val existing = dbInstance
    if(existing != null) return existing
    return synchronized(PenDb::class.java) {
        dbInstance ?: PenDb(ctx.applicationContext).also { dbInstance = it }
    }
}

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
    val out = mutableListOf<StockEntry>()
    try {
        db(ctx).readableDatabase.rawQuery("SELECT id,model,imei,hsn,cost,mrp,qty,supplier,addedOn,brand,cls FROM stock ORDER BY model", null).use { c ->
            while(c.moveToNext()) out.add(StockEntry(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "",
                c.getDouble(4), c.getDouble(5), c.getInt(6), c.getString(7) ?: "", c.getString(8) ?: "",
                c.getString(9) ?: "", c.getString(10) ?: ""))
        }
    } catch(e: Exception) { }
    return out
}
fun saveStock(ctx: Context, list: List<StockEntry>) {
    val w = db(ctx).writableDatabase
    w.beginTransaction()
    try {
        w.delete("stock", null, null)
        for(e in list) {
            val cv = android.content.ContentValues()
            cv.put("id", e.id); cv.put("model", e.model); cv.put("imei", e.imei); cv.put("hsn", e.hsn)
            cv.put("cost", e.cost); cv.put("mrp", e.mrp); cv.put("qty", e.qty); cv.put("supplier", e.supplier); cv.put("addedOn", e.addedOn)
            cv.put("brand", e.brand); cv.put("cls", e.cls)
            w.insertWithOnConflict("stock", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        }
        w.setTransactionSuccessful()
    } finally { w.endTransaction() }
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

// Everything needed to stand the app up on a fresh install: all settings, plus the
// invoice and ledger records so the GST tab is not empty after a restore.
// The PIN is deliberately left out of the file.
fun backupJson(ctx: Context): String {
    val prefs = ctx.getSharedPreferences("penkhata_data", Context.MODE_PRIVATE)
    val settings = JSONObject()
    for((k, v) in prefs.all) { if(k != "appPin" && v != null) settings.put(k, v) }
    val invoices = JSONArray(); val ledger = JSONArray(); val expenses = JSONArray(); val stockArr = JSONArray()
    try {
        db(ctx).readableDatabase.rawQuery("SELECT ms FROM bills ORDER BY ms", null).use { c ->
            while(c.moveToNext()) billJson(ctx, c.getLong(0))?.let { invoices.put(it) }
        }
    } catch(e: Exception) { }
    try {
        db(ctx).readableDatabase.rawQuery("SELECT id,date,party,type,amt,descr FROM ledger ORDER BY id", null).use { c ->
            while(c.moveToNext()) {
                val o = JSONObject()
                o.put("id", c.getLong(0)); o.put("date", c.getString(1) ?: ""); o.put("party", c.getString(2) ?: "")
                o.put("type", c.getString(3) ?: ""); o.put("amt", c.getDouble(4)); o.put("desc", c.getString(5) ?: "")
                ledger.put(o)
            }
        }
    } catch(e: Exception) { }
    try {
        db(ctx).readableDatabase.rawQuery("SELECT id,ms,note,amount FROM expenses ORDER BY ms", null).use { c ->
            while(c.moveToNext()) {
                val o = JSONObject()
                o.put("id", c.getLong(0)); o.put("ms", c.getLong(1)); o.put("note", c.getString(2) ?: ""); o.put("amount", c.getDouble(3))
                expenses.put(o)
            }
        }
    } catch(e: Exception) { }
    try {
        db(ctx).readableDatabase.rawQuery("SELECT id,model,imei,hsn,cost,mrp,qty,supplier,addedOn FROM stock", null).use { c ->
            while(c.moveToNext()) {
                val o = JSONObject()
                o.put("id", c.getLong(0)); o.put("model", c.getString(1) ?: ""); o.put("imei", c.getString(2) ?: "")
                o.put("hsn", c.getString(3) ?: ""); o.put("cost", c.getDouble(4)); o.put("mrp", c.getDouble(5))
                o.put("qty", c.getInt(6)); o.put("supplier", c.getString(7) ?: ""); o.put("addedOn", c.getString(8) ?: "")
                stockArr.put(o)
            }
        }
    } catch(e: Exception) { }
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

// ---------------------------------------------------------------- xlsx export
// XLSX is a zip of XML. Writing it by hand keeps Apache POI (several MB) out of the app.
fun xmlEscape(v: String): String = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
fun colName(i: Int): String { var n = i + 1; var out = ""; while(n > 0) { val r = (n - 1) % 26; out = ('A' + r) + out; n = (n - 1) / 26 }; return out }
fun sheetXml(rows: List<List<String>>): String {
    val sb = StringBuilder()
    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
    sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
    rows.forEachIndexed { r, row ->
        sb.append("<row r=\"${r+1}\">")
        row.forEachIndexed { c, v ->
            if(v.isNotBlank()) sb.append("<c r=\"${colName(c)}${r+1}\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xmlEscape(v)}</t></is></c>")
        }
        sb.append("</row>")
    }
    sb.append("</sheetData></worksheet>")
    return sb.toString()
}
fun xlsxBytes(sheets: List<Pair<String, List<List<String>>>>): ByteArray {
    val bos = java.io.ByteArrayOutputStream()
    java.util.zip.ZipOutputStream(bos).use { z ->
        fun put(name: String, content: String) {
            z.putNextEntry(java.util.zip.ZipEntry(name)); z.write(content.toByteArray(Charsets.UTF_8)); z.closeEntry()
        }
        val ct = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        ct.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        ct.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        ct.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        ct.append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
        sheets.forEachIndexed { i, _ ->
            ct.append("<Override PartName=\"/xl/worksheets/sheet${i+1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
        }
        ct.append("</Types>")
        put("[Content_Types].xml", ct.toString())
        put("_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
        val wb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>")
        sheets.forEachIndexed { i, sh -> wb.append("<sheet name=\"${xmlEscape(sh.first.take(28))}\" sheetId=\"${i+1}\" r:id=\"rId${i+1}\"/>") }
        wb.append("</sheets></workbook>")
        put("xl/workbook.xml", wb.toString())
        val rels = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        sheets.forEachIndexed { i, _ -> rels.append("<Relationship Id=\"rId${i+1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${i+1}.xml\"/>") }
        rels.append("</Relationships>")
        put("xl/_rels/workbook.xml.rels", rels.toString())
        sheets.forEachIndexed { i, sh -> put("xl/worksheets/sheet${i+1}.xml", sheetXml(sh.second)) }
    }
    return bos.toByteArray()
}

// The Installation Register: one row per item sold, in the column order brand
// claim teams ask for. This is the sheet the shop files, not a generic export.
fun buildRegisterXlsx(ctx: Context, startMs: Long, endMs: Long): ByteArray {
    val prefs = ctx.getSharedPreferences("penkhata_data", Context.MODE_PRIVATE)
    val branch = loadBranches(ctx).firstOrNull { it.code == activeBranchCode(prefs) } ?: loadBranches(ctx).firstOrNull()
    val salesmanPhone = loadPeople(ctx).associate { it.name to it.phone }
    val rows = mutableListOf<List<String>>()
    rows.add(listOf(prefs.getString("sName", "") ?: ""))
    rows.add(listOf("REGD OFFICE : " + (prefs.getString("sAddr1", "") ?: "")))
    rows.add(listOf("Branch : " + (branch?.code ?: "") + (if((branch?.gstin ?: "").isBlank()) "" else " (GSTIN : " + branch!!.gstin + ")")))
    rows.add(listOf("BRANCH ADDRESS : " + (branch?.address ?: "")))
    rows.add(listOf("Installation Register"))
    val fmtD = SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH)
    rows.add(listOf("Period : " + fmtD.format(Date(startMs)) + " To " + fmtD.format(Date(endMs - 1)) + " (Financial Year : " + fyLabel() + ")"))
    rows.add(listOf("Sl.","Branch","Date","Bill Number","Product Name","Customer Name","Address","Pincode","District",
        "Delivery Address","Near Location","Mobile No","Phone","Delivery Dt","Remarks","Brand","Class","Salesman",
        "Salesman MobNo","Product Value","Qty","Product Srl No."))
    var sl = 0
    try {
        db(ctx).readableDatabase.rawQuery(
            "SELECT inv,date,buyer,baddr1,baddr2,bpin,bstate,blmk,bphone,branch,salesman,deliveryDate,cancelReason,lines FROM bills " +
            "WHERE quote=0 AND cancelled=0 AND ms>=? AND ms<? ORDER BY ms",
            arrayOf(startMs.toString(), endMs.toString())).use { c ->
            while(c.moveToNext()) {
                val addr = listOf(c.getString(3) ?: "", c.getString(4) ?: "").filter { it.isNotBlank() }.joinToString(", ")
                val arr = try { JSONArray(c.getString(13) ?: "[]") } catch(e: Exception) { JSONArray() }
                for(i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val qty = o.optDouble("qty", 1.0)
                    val value = o.optDouble("taxable", 0.0) + o.optDouble("tax", 0.0)
                    val salesman = c.getString(10) ?: ""
                    sl++
                    rows.add(listOf(sl.toString(), c.getString(9) ?: "", c.getString(1) ?: "", c.getString(0) ?: "",
                        o.optString("desc"), c.getString(2) ?: "", addr, c.getString(5) ?: "", c.getString(6) ?: "",
                        addr, c.getString(7) ?: "", c.getString(8) ?: "", c.getString(8) ?: "",
                        c.getString(11) ?: "", c.getString(12) ?: "",
                        o.optString("brand"), o.optString("cls"), salesman, salesmanPhone[salesman] ?: "",
                        fmt(value), if(qty == qty.toLong().toDouble()) qty.toLong().toString() else fmt(qty), o.optString("serial")))
                }
            }
        }
    } catch(e: Exception) { }
    return xlsxBytes(listOf("Register" to rows))
}

// Brand-wise sales for a period: what each brand actually did.
fun brandSales(ctx: Context, startMs: Long, endMs: Long): List<Triple<String, Double, Double>> {
    val map = mutableMapOf<String, Pair<Double, Double>>()
    try {
        db(ctx).readableDatabase.rawQuery("SELECT lines FROM bills WHERE quote=0 AND cancelled=0 AND ms>=? AND ms<?",
            arrayOf(startMs.toString(), endMs.toString())).use { c ->
            while(c.moveToNext()) {
                val arr = try { JSONArray(c.getString(0) ?: "[]") } catch(e: Exception) { JSONArray() }
                for(i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val b = o.optString("brand").ifBlank { "UNBRANDED" }
                    val val0 = o.optDouble("taxable", 0.0) + o.optDouble("tax", 0.0)
                    val cur = map[b] ?: (0.0 to 0.0)
                    map[b] = (cur.first + val0) to (cur.second + o.optDouble("qty", 0.0))
                }
            }
        }
    } catch(e: Exception) { }
    return map.entries.map { Triple(it.key, it.value.first, it.value.second) }.sortedByDescending { it.second }
}

// The same data as the backup, in a workbook you can open and read yourself.
// CSV for a spreadsheet or an accountant. Fields with commas, quotes or newlines
// are quoted, with inner quotes doubled.
fun csvField(v: String): String {
    return if(v.contains(',') || v.contains('"') || v.contains('\n')) "\"" + v.replace("\"", "\"\"") + "\"" else v
}
fun csvRow(cells: List<String>): String = cells.joinToString(",") { csvField(it) } + "\r\n"
fun exportCsvBills(ctx: Context, startMs: Long, endMs: Long): String {
    val sb = StringBuilder()
    sb.append(csvRow(listOf("Invoice","Date","Buyer","Phone","GSTIN","State","Taxable","CGST","SGST","IGST","Total","Paid","Balance","Cost","Profit")))
    try {
        db(ctx).readableDatabase.rawQuery(
            "SELECT inv,date,buyer,bphone,bgst,bstate,taxable,cgst,sgst,igst,total,paid,balance,cogs,profit FROM bills WHERE quote=0 AND ms>=? AND ms<? ORDER BY ms",
            arrayOf(startMs.toString(), endMs.toString())).use { c ->
            while(c.moveToNext()) {
                val cells = mutableListOf<String>()
                for(i in 0..4) cells.add(c.getString(i) ?: "")
                for(i in 5..14) cells.add(fmt(c.getDouble(i)))
                sb.append(csvRow(cells))
            }
        }
    } catch(e: Exception) { }
    return sb.toString()
}
fun exportWorkbook(ctx: Context): ByteArray {
    val inv = mutableListOf(listOf("Invoice","Date","Buyer","Phone","GSTIN","State","Taxable","CGST","SGST","IGST","Total","Paid","Balance","Profit"))
    val lines = mutableListOf(listOf("Invoice","Description","Serial/IMEI","HSN","Qty","Rate","GST %","Taxable","Tax"))
    val led = mutableListOf(listOf("Date","Party","Type","Amount","Note"))
    val stk = mutableListOf(listOf("Model","IMEI","HSN","Cost","MRP","Qty","Supplier"))
    val exp = mutableListOf(listOf("Date","Note","Amount"))
    val set = mutableListOf(listOf("Setting","Value"))
    try {
        db(ctx).readableDatabase.rawQuery("SELECT ms,inv,date,buyer,bphone,bgst,bstate,taxable,cgst,sgst,igst,total,paid,balance,profit,lines FROM bills ORDER BY ms", null).use { c ->
            while(c.moveToNext()) {
                inv.add(listOf(c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "", c.getString(4) ?: "", c.getString(5) ?: "",
                    c.getString(6) ?: "", fmt(c.getDouble(7)), fmt(c.getDouble(8)), fmt(c.getDouble(9)), fmt(c.getDouble(10)),
                    fmt(c.getDouble(11)), fmt(c.getDouble(12)), fmt(c.getDouble(13)), fmt(c.getDouble(14))))
                try {
                    val arr = JSONArray(c.getString(15) ?: "[]")
                    for(i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        lines.add(listOf(c.getString(1) ?: "", o.optString("desc"), o.optString("serial"), o.optString("hsn"),
                            qtyText(o.optDouble("qty", 0.0)), fmt(o.optDouble("rate", 0.0)), fmt(o.optDouble("taxRate", 0.0)),
                            fmt(o.optDouble("taxable", 0.0)), fmt(o.optDouble("tax", 0.0))))
                    }
                } catch(e: Exception) { }
            }
        }
        db(ctx).readableDatabase.rawQuery("SELECT date,party,type,amt,descr FROM ledger ORDER BY id", null).use { c ->
            while(c.moveToNext()) led.add(listOf(c.getString(0) ?: "", c.getString(1) ?: "", c.getString(2) ?: "", fmt(c.getDouble(3)), c.getString(4) ?: ""))
        }
        db(ctx).readableDatabase.rawQuery("SELECT model,imei,hsn,cost,mrp,qty,supplier FROM stock ORDER BY model", null).use { c ->
            while(c.moveToNext()) stk.add(listOf(c.getString(0) ?: "", (c.getString(1) ?: "").replace("\n", ", "), c.getString(2) ?: "",
                fmt(c.getDouble(3)), fmt(c.getDouble(4)), c.getInt(5).toString(), c.getString(6) ?: ""))
        }
        db(ctx).readableDatabase.rawQuery("SELECT ms,note,amount FROM expenses ORDER BY ms", null).use { c ->
            while(c.moveToNext()) exp.add(listOf(SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH).format(Date(c.getLong(0))), c.getString(1) ?: "", fmt(c.getDouble(2))))
        }
    } catch(e: Exception) { }
    val prefs = ctx.getSharedPreferences("penkhata_data", Context.MODE_PRIVATE)
    for(k in prefs.all.keys.sorted()) {
        if(k == "appPin") continue
        set.add(listOf(k, prefs.all[k]?.toString() ?: ""))
    }
    return xlsxBytes(listOf("Invoices" to inv, "Invoice Lines" to lines, "Ledger" to led, "Stock" to stk, "Expenses" to exp, "Settings" to set))
}

// ---------------------------------------------------------------- encryption
// AES-256-GCM with a PBKDF2 key. Header: "PKH1" + salt(16) + iv(12) + ciphertext.
fun encryptBytes(data: ByteArray, password: String): ByteArray {
    val salt = ByteArray(16); java.security.SecureRandom().nextBytes(salt)
    val iv = ByteArray(12); java.security.SecureRandom().nextBytes(iv)
    val spec = javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, 120000, 256)
    val key = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
    val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key.encoded, "AES"), javax.crypto.spec.GCMParameterSpec(128, iv))
    val out = java.io.ByteArrayOutputStream()
    out.write("PKH1".toByteArray()); out.write(salt); out.write(iv); out.write(cipher.doFinal(data))
    return out.toByteArray()
}
fun isEncryptedBackup(data: ByteArray): Boolean =
    data.size > 32 && data[0] == 'P'.code.toByte() && data[1] == 'K'.code.toByte() && data[2] == 'H'.code.toByte() && data[3] == '1'.code.toByte()
fun decryptBytes(data: ByteArray, password: String): ByteArray? {
    return try {
        val salt = data.copyOfRange(4, 20); val iv = data.copyOfRange(20, 32); val body = data.copyOfRange(32, data.size)
        val spec = javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, 120000, 256)
        val key = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key.encoded, "AES"), javax.crypto.spec.GCMParameterSpec(128, iv))
        cipher.doFinal(body)
    } catch(e: Exception) { null }
}

// Backup content: the JSON the app restores from, plus the workbook a human can read.
fun backupPayload(ctx: Context): ByteArray {
    val bos = java.io.ByteArrayOutputStream()
    java.util.zip.ZipOutputStream(bos).use { z ->
        z.putNextEntry(java.util.zip.ZipEntry("backup.json")); z.write(backupJson(ctx).toByteArray(Charsets.UTF_8)); z.closeEntry()
        z.putNextEntry(java.util.zip.ZipEntry("PenKhata-data.xlsx")); z.write(exportWorkbook(ctx)); z.closeEntry()
        ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?.listFiles()
            ?.filter { it.extension.equals("pdf", true) }
            ?.forEach { f ->
                z.putNextEntry(java.util.zip.ZipEntry("pdf/" + f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
    }
    return bos.toByteArray()
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

// One bill row from the JSON shape used by the backup and by the importer.
fun insertBillRow(w: android.database.sqlite.SQLiteDatabase, o: JSONObject, ms: Long) {
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
fun restoreJson(ctx: Context, text: String): String {
    val root = try { JSONObject(text) } catch(e: Exception) { return "That file is not a PenKhata backup" }
    if(root.optString("type") != "penkhata-backup") return "That file is not a PenKhata backup"
    val prefs = ctx.getSharedPreferences("penkhata_data", Context.MODE_PRIVATE)
    val ed = prefs.edit()
    var settingsCount = 0
    root.optJSONObject("settings")?.let { settings ->
        for(k in settings.keys()) {
            if(k == "appPin") continue
            val raw = settings.opt(k)
            if(raw is Number) ed.putInt(k, raw.toInt()) else ed.putString(k, raw?.toString() ?: "")
            settingsCount++
        }
    }
    ed.apply()
    val w = db(ctx).writableDatabase
    var inv = 0; var led = 0; var exp = 0; var st = 0
    w.beginTransaction()
    try {
        // a restore replaces what is there, rather than merging two shops together
        for(t in listOf("bills", "ledger", "expenses", "stock")) w.delete(t, null, null)
        root.optJSONArray("invoices")?.let { arr ->
            for(i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                insertBillRow(w, o, o.optLong("ms", System.currentTimeMillis() + i)); inv++
            }
        }
        root.optJSONArray("ledger")?.let { arr ->
            for(i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val cv = android.content.ContentValues()
                cv.put("id", o.optLong("id", System.currentTimeMillis() + i)); cv.put("date", o.optString("date"))
                cv.put("party", o.optString("party")); cv.put("type", o.optString("type"))
                cv.put("amt", o.optDouble("amt", 0.0)); cv.put("descr", o.optString("desc"))
                w.insertWithOnConflict("ledger", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE); led++
            }
        }
        root.optJSONArray("expenses")?.let { arr ->
            for(i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val cv = android.content.ContentValues()
                cv.put("id", o.optLong("id", System.currentTimeMillis() + i)); cv.put("ms", o.optLong("ms", System.currentTimeMillis() + i))
                cv.put("note", o.optString("note")); cv.put("amount", o.optDouble("amount", 0.0))
                w.insertWithOnConflict("expenses", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE); exp++
            }
        }
        root.optJSONArray("stock")?.let { arr ->
            for(i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val cv = android.content.ContentValues()
                cv.put("id", o.optLong("id", System.currentTimeMillis() + i)); cv.put("model", o.optString("model"))
                cv.put("imei", o.optString("imei")); cv.put("hsn", o.optString("hsn"))
                cv.put("cost", o.optDouble("cost", 0.0)); cv.put("mrp", o.optDouble("mrp", 0.0))
                cv.put("qty", o.optInt("qty", 0)); cv.put("supplier", o.optString("supplier")); cv.put("addedOn", o.optString("addedOn"))
                w.insertWithOnConflict("stock", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE); st++
            }
        }
        w.setTransactionSuccessful()
    } catch(e: Exception) {
        return "Restore failed: " + (e.message ?: "unknown error")
    } finally {
        w.endTransaction()
    }
    // backups made before 3.6.2 carry no tax columns; rebuild them from the lines
    val repaired = repairAggregates(ctx)
    return "Restored $settingsCount settings, $inv invoices, $led ledger entries, $exp expenses, $st stock lines." +
           (if(repaired > 0) " Rebuilt tax for $repaired old bill(s)." else "")
}

fun saveInvoiceData(ctx: Context, ms: Long, isQuote: Boolean, invNo: String, dateStr: String, payMode: String, delNote: String,
                    b: BuyerInfo, sel: SellerInfo, billDiscount: Double, exchange: Double, cash: Double, upi: Double, card: Double,
                    finance: String, calc: BillCalc, items: List<InvItem>) {
    val stock = loadStock(ctx)
    var cogs = 0.0
    for(i in items) cogs += stockCostFor(stock, i) * i.qty
    // per-line figures, scaled by the bill discount, so rate-wise summaries add up
    var baseSum = 0.0
    val divisor = discountDivisor(ctx.getSharedPreferences("penkhata_data", Context.MODE_PRIVATE))
    for(i in items) { val g=i.qty*i.rate; val net=netAfterDiscount(g, i.discount, divisor); baseSum += net/(1+i.taxRate.coerceIn(0.0,100.0)/100.0) }
    val keep = if(baseSum > 0.0) calc.taxable / baseSum else 1.0
    val lines = JSONArray()
    for(i in items) {
        val g = i.qty*i.rate; val net = netAfterDiscount(g, i.discount, divisor)
        val tr = i.taxRate.coerceIn(0.0,100.0); val base = net/(1+tr/100.0)
        val o = JSONObject()
        o.put("desc", i.desc); o.put("hsn", i.hsn); o.put("qty", i.qty); o.put("rate", i.rate)
        o.put("serial", i.serial); o.put("unit", i.unit); o.put("discount", i.discount)
        o.put("taxRate", tr); o.put("taxable", base*keep); o.put("tax", (net-base)*keep)
        o.put("brand", i.brand); o.put("cls", i.cls)
        lines.put(o)
    }
    val sj = JSONObject()
    sj.put("name", sel.name); sj.put("addr", sel.addr); sj.put("phone", sel.phone); sj.put("email", sel.email)
    sj.put("landmark", sel.landmark); sj.put("pincode", sel.pincode); sj.put("gst", sel.gst)
    sj.put("bank", sel.bank); sj.put("ifsc", sel.ifsc); sj.put("juris", sel.juris); sj.put("upi", sel.upi); sj.put("state", sel.state)
    val cv = android.content.ContentValues()
    cv.put("ms", ms); cv.put("quote", if(isQuote) 1 else 0); cv.put("inv", invNo); cv.put("date", dateStr)
    cv.put("payMode", payMode); cv.put("delNote", delNote)
    cv.put("buyer", b.name); cv.put("bgst", b.gst); cv.put("baddr1", b.addr1); cv.put("baddr2", b.addr2)
    cv.put("blmk", b.landmark); cv.put("bpin", b.pincode); cv.put("bphone", b.phone); cv.put("bstate", b.state)
    cv.put("total", calc.grand); cv.put("taxable", calc.taxable); cv.put("cgst", calc.cgst); cv.put("sgst", calc.sgst)
    cv.put("igst", calc.igst); cv.put("tax", calc.totalTax); cv.put("interState", if(calc.interState) 1 else 0)
    cv.put("cogs", cogs); cv.put("profit", calc.taxable - cogs); cv.put("paid", calc.paid); cv.put("balance", calc.balance)
    cv.put("bd", billDiscount); cv.put("exch", exchange); cv.put("cash", cash); cv.put("upi", upi); cv.put("card", card)
    cv.put("finance", finance); cv.put("seller", sj.toString()); cv.put("lines", lines.toString())
    // who did it and from which outlet: the basis of every later dispute
    val pref = ctx.getSharedPreferences("penkhata_data", Context.MODE_PRIVATE)
    val br = loadBranches(ctx).firstOrNull { it.code == activeBranchCode(pref) } ?: loadBranches(ctx).firstOrNull()
    cv.put("branch", br?.code ?: ""); cv.put("series", br?.series ?: "")
    cv.put("salesman", activePerson(pref)); cv.put("deliveryDate", dateStr)
    upsertCustomer(ctx, b, ms)
    db(ctx).writableDatabase.insertWithOnConflict("bills", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
}
fun saveLedgerEntry(ctx: Context, e: LedgerEntry) {
    val cv = android.content.ContentValues()
    cv.put("id", System.currentTimeMillis()); cv.put("date", e.date); cv.put("party", e.party)
    cv.put("type", e.type); cv.put("amt", e.amount); cv.put("descr", e.desc)
    db(ctx).writableDatabase.insertWithOnConflict("ledger", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
}
fun loadLedger(ctx: Context): List<LedgerEntry> {
    val l = mutableListOf<LedgerEntry>()
    try {
        db(ctx).readableDatabase.rawQuery("SELECT id,date,party,type,amt,descr FROM ledger ORDER BY id", null).use { c ->
            while(c.moveToNext()) l.add(LedgerEntry(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "", c.getDouble(4), c.getString(5) ?: ""))
        }
    } catch(e: Exception) { }
    return l
}
fun deleteLedgerEntry(ctx: Context, id: Long) {
    try { db(ctx).writableDatabase.delete("ledger", "id=?", arrayOf(id.toString())) } catch(e: Exception) { }
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
        val discNote = if(it.discount>0.004) "Disc ${fmt(it.discount)}%  -${fmt(discountAmount(it.qty*it.rate, it.discount, calc.discountDivisor))}" else ""
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
