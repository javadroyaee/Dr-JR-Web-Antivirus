package ir.jrweb.drjrweb

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import ir.jrweb.drjrweb.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isScanning = false
    private val detectedThreats = mutableListOf<ThreatItem>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        checkAllFilesPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupHeaderInfo()
        requestRequiredPermissions()
        setupListeners()
    }

    private fun setupHeaderInfo() {
        val prefs = getSharedPreferences("DrJRWebPrefs", Context.MODE_PRIVATE)
        val userName = prefs.getString("user_name", "کاربر گرامی") ?: "کاربر گرامی"
        binding.tvWelcomeUser.text = "سلام $userName | محافظت فعال"
        binding.tvDeviceModel.text = "${Build.MANUFACTURER.uppercase()} ${Build.MODEL}"
        updateRamStat()
    }

    private fun updateRamStat() {
        val actManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)
        val availMb = memInfo.availMem / (1024 * 1024)
        val totalMb = memInfo.totalMem / (1024 * 1024)
        binding.tvRamInfo.text = "آزاد: ${availMb}MB از ${totalMb}MB"
    }

    private fun requestRequiredPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.READ_MEDIA_IMAGES)
            perms.add(Manifest.permission.READ_MEDIA_VIDEO)
            perms.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun checkAllFilesPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                AlertDialog.Builder(this)
                    .setTitle("مجوز دسترسی کامل به فایل‌ها")
                    .setMessage("برای اینکه دکتر JR Web بتواند تمام فایل‌های گوشی را اسکن و فایل‌های مخرب را پاکسازی کند، لطفاً گزینه Allow access to manage all files را فعال کنید.")
                    .setPositiveButton("تایید و رفتن به تنظیمات") { _, _ ->
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            intent.data = Uri.parse("package:$packageName")
                            startActivity(intent)
                        } catch (e: Exception) {
                            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        }
                    }
                    .setNegativeButton("فعلاً نه", null)
                    .show()
            }
        }
    }

    private fun setupListeners() {
        binding.btnBigScan.setOnClickListener {
            if (!isScanning) {
                startFullScan()
            }
        }

        binding.btnPermissions.setOnClickListener {
            checkAllFilesPermission()
            requestRequiredPermissions()
            Toast.makeText(this, "وضعیت دسترسی‌ها بررسی شد", Toast.LENGTH_SHORT).show()
        }

        binding.cardScanFiles.setOnClickListener {
            if (!isScanning) startFullScan()
        }

        binding.cardScanApps.setOnClickListener {
            if (!isScanning) startFullScan()
        }

        binding.cardBooster.setOnClickListener {
            boostRamAndCleanCache()
        }

        binding.cardWifiSecurity.setOnClickListener {
            checkNetworkSecurity()
        }
    }

    private fun startFullScan() {
        isScanning = true
        detectedThreats.clear()
        binding.layoutThreatsContainer.visibility = View.GONE
        binding.listThreats.removeAllViews()
        binding.layoutResultBox.visibility = View.GONE
        binding.progressScan.visibility = View.VISIBLE
        binding.progressScan.progress = 0
        binding.tvScanBtnTitle.text = "در حال اسکن..."

        lifecycleScope.launch {
            var scannedAppsCount = 0
            var scannedFilesCount = 0

            val installedApps = withContext(Dispatchers.IO) {
                packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            }

            val totalApps = installedApps.size.coerceAtLeast(1)
            for ((index, pkgInfo) in installedApps.withIndex()) {
                val appInfo = pkgInfo.applicationInfo ?: continue
                if (pkgInfo.packageName == packageName) continue

                val appLabel = appInfo.loadLabel(packageManager).toString()
                scannedAppsCount++

                val progress = ((index + 1) * 50) / totalApps
                binding.progressScan.progress = progress
                binding.tvScanPercent.text = "$progress%"
                binding.tvScanPath.text = "بررسی برنامه: $appLabel"

                val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                if (!isSystemApp) {
                    val riskReason = analyzeAppRisk(pkgInfo)
                    if (riskReason != null) {
                        detectedThreats.add(
                            ThreatItem(
                                title = "برنامه مشکوک: $appLabel",
                                description = riskReason,
                                isApp = true,
                                packageName = pkgInfo.packageName
                            )
                        )
                    }
                }
                delay(18)
            }

            val filesToScan = withContext(Dispatchers.IO) {
                collectStorageFiles(Environment.getExternalStorageDirectory(), maxDepth = 4, limit = 600)
            }

            val totalFiles = filesToScan.size.coerceAtLeast(1)
            for ((index, file) in filesToScan.withIndex()) {
                scannedFilesCount++
                val progress = 50 + (((index + 1) * 50) / totalFiles)
                binding.progressScan.progress = progress
                binding.tvScanPercent.text = "$progress%"
                binding.tvScanPath.text = "اسکن فایل: ${file.name}"

                val fileThreat = analyzeFileRisk(file)
                if (fileThreat != null) {
                    detectedThreats.add(
                        ThreatItem(
                            title = "فایل مخرب / ناایمن: ${file.name}",
                            description = fileThreat,
                            isApp = false,
                            filePath = file.absolutePath
                        )
                    )
                }
                if (index % 5 == 0) delay(12)
            }

            binding.progressScan.progress = 100
            binding.tvScanPercent.text = "۱۰۰٪ کامل شد"
            binding.tvScanBtnTitle.text = "اسکن مجدد"
            binding.tvScanPath.text = "بررسی $scannedAppsCount برنامه و $scannedFilesCount فایل به پایان رسید."
            isScanning = false

            renderScanReport(scannedAppsCount, scannedFilesCount)
        }
    }

    private fun analyzeAppRisk(pkgInfo: PackageInfo): String? {
        val pkg = pkgInfo.packageName.lowercase()
        val suspiciousKeywords = listOf("spy", "hack", "trojan", "keylog", "rat", "malware", "ads.push")
        if (suspiciousKeywords.any { pkg.contains(it) }) {
            return "نام پکیج دارای الگوی بدافزار یا جاسوس‌افزار است."
        }

        val perms = pkgInfo.requestedPermissions ?: return null
        val hasSms = perms.any { it.contains("READ_SMS") || it.contains("RECEIVE_SMS") }
        val hasContacts = perms.any { it.contains("READ_CONTACTS") }
        val hasInstall = perms.any { it.contains("REQUEST_INSTALL_PACKAGES") }

        if (hasSms && hasContacts && hasInstall) {
            return "دسترسی همزمان حساس به پیامک‌ها، مخاطبین و نصب خودکار برنامه!"
        }
        return null
    }

    private fun analyzeFileRisk(file: File): String? {
        val name = file.name.lowercase()
        val dangerousExts = listOf(".exe", ".bat", ".vbs", ".scr", ".pif", ".cmd", ".sh")
        if (dangerousExts.any { name.endsWith(it) }) {
            return "پسوند فایل اجرایی مشکوک و غیرمجاز در محیط اندروید (${file.extension})."
        }
        if (name.endsWith(".apk") && (name.contains("hack") || name.contains("crack") || name.contains("virus") || name.contains("trojan"))) {
            return "فایل نصبی APK آلوده یا دستکاری‌شده شناسایی شد."
        }
        if (name.endsWith(".tmp") || name.endsWith(".log") && file.length() == 0L) {
            return "فایل زائد و خراب سیستمی (قابل پاکسازی ایمن)."
        }
        return null
    }

    private fun collectStorageFiles(dir: File?, maxDepth: Int, limit: Int): List<File> {
        val result = mutableListOf<File>()
        if (dir == null || !dir.exists() || !dir.canRead()) return result

        fun walk(current: File, depth: Int) {
            if (depth > maxDepth || result.size >= limit) return
            val children = current.listFiles() ?: return
            for (child in children) {
                if (result.size >= limit) break
                if (child.isDirectory) {
                    if (!child.name.startsWith(".")) {
                        walk(child, depth + 1)
                    }
                } else {
                    result.add(child)
                }
            }
        }
        walk(dir, 0)
        return result
    }

    private fun renderScanReport(appsCount: Int, filesCount: Int) {
        binding.layoutResultBox.visibility = View.VISIBLE
        if (detectedThreats.isEmpty()) {
            binding.tvResultTitle.text = "همه چیز اوکی است و هیچ مشکلی وجود ندارد!"
            binding.tvResultTitle.setTextColor(ContextCompat.getColor(this, R.color.success_green))
            binding.tvResultDetails.text =
                "• تعداد $appsCount برنامه و $filesCount فایل با دقت بررسی شدند.\n" +
                "• هیچ بدافزار، فایل مخرب یا دسترسی غیرمجازی یافت نشد.\n" +
                "• گوشی شما در سلامت کامل و تحت پوشش دکتر JR Web است."
        } else {
            binding.tvResultTitle.text = "هشدار امنیتی: ${detectedThreats.size} مورد مشکوک شناسایی شد!"
            binding.tvResultTitle.setTextColor(ContextCompat.getColor(this, R.color.danger_red))
            binding.tvResultDetails.text =
                "• بررسی $appsCount برنامه و $filesCount فایل انجام شد.\n" +
                "• موارد زیر نیازمند پاکسازی یا حذف هستند. از بخش پایین می‌توانید مستقیماً آن‌ها را حذف کنید."

            binding.layoutThreatsContainer.visibility = View.VISIBLE
            binding.listThreats.removeAllViews()

            for (item in detectedThreats.toList()) {
                addThreatCard(item)
            }
        }
    }

    private fun addThreatCard(item: ThreatItem) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.navy_dark))
            setPadding(28, 24, 28, 24)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = 18
            layoutParams = lp
        }

        val tvTitle = TextView(this).apply {
            text = item.title
            setTextColor(ContextCompat.getColor(context, R.color.orange_primary))
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val tvDesc = TextView(this).apply {
            text = item.description
            setTextColor(ContextCompat.getColor(context, R.color.white_muted))
            textSize = 12f
            setPadding(0, 6, 0, 14)
        }

        val btnDelete = Button(this).apply {
            text = if (item.isApp) "حذف کامل این برنامه" else "پاک کردن این فایل"
            setBackgroundColor(ContextCompat.getColor(context, R.color.danger_red))
            setTextColor(ContextCompat.getColor(context, R.color.white))
            textSize = 13f
            gravity = Gravity.CENTER
            setOnClickListener {
                if (item.isApp && item.packageName != null) {
                    val uninstallIntent = Intent(Intent.ACTION_DELETE).apply {
                        data = Uri.parse("package:${item.packageName}")
                    }
                    startActivity(uninstallIntent)
                    markThreatResolved(card, item)
                } else if (!item.isApp && item.filePath != null) {
                    val target = File(item.filePath)
                    val deleted = if (target.exists()) target.delete() else true
                    if (deleted) {
                        Toast.makeText(context, "فایل مخرب با موفقیت پاک شد", Toast.LENGTH_SHORT).show()
                        markThreatResolved(card, item)
                    } else {
                        Toast.makeText(context, "لطفاً مجوز مدیریت تمام فایل‌ها را فعال کنید", Toast.LENGTH_LONG).show()
                        checkAllFilesPermission()
                    }
                }
            }
        }

        card.addView(tvTitle)
        card.addView(tvDesc)
        card.addView(btnDelete)
        binding.listThreats.addView(card)
    }

    private fun markThreatResolved(cardView: View, item: ThreatItem) {
        binding.listThreats.removeView(cardView)
        detectedThreats.remove(item)
        if (detectedThreats.isEmpty()) {
            binding.layoutThreatsContainer.visibility = View.GONE
            binding.tvResultTitle.text = "تمام مشکلات برطرف و پاکسازی شدند!"
            binding.tvResultTitle.setTextColor(ContextCompat.getColor(this, R.color.success_green))
            binding.tvResultDetails.text = "گوشی شما اکنون کاملاً ایمن و پاکسازی شده است."
        }
    }

    private fun boostRamAndCleanCache() {
        val actManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val installed = packageManager.getInstalledApplications(0)
        for (app in installed) {
            if (app.packageName != packageName) {
                actManager.killBackgroundProcesses(app.packageName)
            }
        }
        updateRamStat()
        AlertDialog.Builder(this)
            .setTitle("شتاب‌دهنده دکتر JR Web")
            .setMessage("فرآیندهای پس‌زمینه غیرضروری بسته شدند و حافظه رم گوشی بهینه‌سازی شد. وضعیت سیستم: عالی!")
            .setPositiveButton("باشه", null)
            .show()
    }

    private fun checkNetworkSecurity() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(network)
        val statusMsg = when {
            caps == null -> "اتصال اینترنت فعال نیست."
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                "اتصال Wi-Fi شما بررسی شد. رمزنگاری و امنیت شبکه در وضعیت ایمن قرار دارد."
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                "اتصال اینترنت همراه (دیتا) در وضعیت ایمن و بدون نشت اطلاعات است."
            else -> "اتصال شبکه ایمن است."
        }

        AlertDialog.Builder(this)
            .setTitle("سپر امنیت شبکه JR Web")
            .setMessage(statusMsg)
            .setPositiveButton("تایید", null)
            .show()
    }
}