# مرجع ریپوهای پروژهٔ سهند سرویس (REPOS.md)

> این فایل مرجع، نقشهٔ کامل ریپوها، محصولات و روابط آن‌هاست تا در هر زمان — حتی ماه‌ها بعد — بدون جستجو مشخص باشد هر ریپو «چیست»، «چه چیزی داخلش است» و «با کدام ریپوها چگونه در ارتباط است».
> به‌روزرسانی: v2.12.7 / v1.2.4 / v2.11.2 — این فایل در **هر چهار ریپو** (cPanel · VPS · LM · Android) با محتوای یکسان نگهداری می‌شود.

---

## ۱) نمای کلی اکوسیستم

```
        ┌──────────────────────────────────────────────────────────┐
        │                    GitHub (pranses2011)                    │
        │  cPanel_Sahand_Service · Sahand_Service · License_Management │
        │  Android_Sahand_Service (اپ‌های اندروید — v2.11.2+)         │
        └──────────────────────────────────────────────────────────┘
                        │ clone/build
        ┌───────────────┼──────────────────┐
        ▼               ▼                  ▼
  پنل cPanel      پنل VPS         سرور لایسنس (LM)
  (سرویس‌دهنده    (سرویس‌دهنده     (مرکزی — یک نمونه
   اشتراکی cPanel) VPS/Docker)      برای فروشنده)
        │               │                  ▲
        │  check/activate/verify/heartbeat │
        └───────────────┴──────────────────┘
                        │
        ┌───────────────┴──────────────────┐
        ▼                                  ▼
  اپ اندروید «سهند سرویس»          پنل وب مدیریت لایسنس
  (دو flavor: نمایندگی/سرویس‌کار —  (admin/ — فقط سوپرادمین
   WebView همان پنل + اعلان‌ها)      فروشنده → مدیریت لایسنس‌ها)
```

- **چهار ریپو**: دو «محصول پنل» از یک کد‌بیس مشترک (cPanel کامپایل‌شده + PHP؛ VPS سورس Next.js + Prisma/Docker)، یک «سرور لایسنس» مرکزی و یک «ریپوی اپ‌های اندروید» (v2.12.7+).
- **اپ اندروید مشتری** (نمایندگی/سرویس‌کار): سورس کامل **فقط** در ریپوی **Android_Sahand_Service** (از v2.11.2 — قبلاً در `android/` ریپوی cPanel بود و دوگانگی می‌ساخت؛ آن پوشه حالا فقط README اشاره‌گر + APKهای نهایی در `downloads/` دارد) — WebView پنل + اعلان‌های سیستمی + بروزرسانی خودکار.
- **اپ اندروید مدیر لایسنس** (SahandLicenseAdmin): سورس در ریپوی **License_Management** (`android/`) — اپ مخصوص پنل سوپرادمین LM، جزو «اپ‌های مشتری سهند سرویس» نیست.

---

## ۲) ریپوها

### ۲-۱) `pranses2011/cPanel_Sahand_Service` — پنل سهند سرویس (نسخهٔ cPanel)
- **چیست:** محصول اصلی برای هاست اشتراکی cPanel (ایران). فرانت = بیلد Next.js (چانک‌های کامپایل‌شده در `_next/static/chunks/`)، بک‌اند = PHP خالص در `api/`، دیتابیس = MySQL (در تست: SQLite).
- **ساختارهای کلیدی:**
  - `api/routes/*.php` — نقاط انتهایی REST (`entity.php`, `chat.php`, `messengers.php`, `notification.php`, `license.php`, `tech-log.php`, `saved-reports.php`, `app-heartbeat.php` …)
  - `api/lib/` — هسته (`orm.php`, `schema_registry.php`, `license.php` (کلاینت لایسنس + کلید عمومی RSA), `messengers.php` (دروازه‌های پیام‌رسان + GAS), `mailer.php` …)
  - `i18n/admin-ext.js` — **بخش بزرگی از UI فارسی/رفتارهای صفحه‌ای** که روی چانک‌های کامپایل‌شده سوار می‌شود (overlay ها، صفحات افزوده مثل پیام‌رسان‌ها، گزارش‌ساز، …). پچ چانک‌ها = الگوی `scripts/patch-*.py|mjs`.
  - `_next/static/chunks/1d80c455cd35437f.js` و `41f31edbe69caf3b.js` — چانک‌های اصلی (روتر agency/tech، صفحات زیادی از آن‌ها رندر می‌شود)؛ **هر تغییری اینجا باید با اسکریپت پایتون دقیق و قابل تکرار انجام شود**.
  - `install/schema.sql` + `api/lib/schema_registry.php` — DDL و خودمهاجرتی.
  - `android/` — فقط README اشاره‌گر به ریپوی **Android_Sahand_Service** (از v2.12.7 سورس اپ آنجاست؛ اینجا دیگر سورس نگه داشته نمی‌شود تا دوگانگی پیش نیاید).
  - `downloads/SahandService-*.apk` + `android-app.json` — **فایل‌های نهایی** برای بروزرسانی خودکار اپ (سروинг از پنل).
  - `api/lib/mail-relay.php` — v2.12.7: ارسال تضمینی ایمیل (SMTP محلی ↔ رلهٔ `v1/send-mail` سرور لایسنس) + کپی مخفی پشتیبان به مدیر ارشد (`v1/backup-copy`).
  - `api/routes/app-heartbeat.php` — v2.12.7: پسوند نقش در deviceId (دو اپ یک گوشی = دو دستگاه) + فوروارد meta گوشی.
  - `version.json` — نسخهٔ محصول (پنل خودش + اپ اندروید با آن چک می‌شود).
- **با چه ریپوهایی ارتباط دارد:**
  - → **License_Management**: در ورود/روزانه/۳۰روزه `POST {license-server}/api/v1/check|verify|activate` با `{licenseKey, domain, product:'cpanel', …}`؛ پاسخ امضاشدهٔ RSA-SHA256 را با کلید عمومی embed شده تأیید و کش می‌کند. ماژول‌ها/سقف‌ها از همین پاسخ می‌آیند.
  - ↔ **Sahand_Service (VPS)**: خواهرِ هم‌قابلیت — هر فیکس/فیچر معمولاً **در هر دو** به‌شکل متناسب پیاده می‌شود (PHP/چانک در این ریپو؛ TS/React بومی در VPS).
  - ← اپ اندروید (`android/` داخل همین ریپو): اپ این پنل را WebView می‌کند؛ زنجیرهٔ اعلان و heartbeat از اپ به API همین پنل و سپس به سرور لایسنس می‌رود.

### ۲-۲) `pranses2011/Sahand_Service` — پنل سهند سرویس (نسخهٔ VPS)
- **چیست:** همان محصول، برای استقرار روی VPS با Docker (سورس کامل Next.js + Prisma + Next API routes).
- **ساختارهای کلیدی:**
  - `src/components/pages/**` — صفحات React (از جمله `partner-group.tsx`, `messengers`…, `lazy-pages-2.tsx` حاوی صفحات lazy)
  - `src/app/api/**` — API routes معادل PHP (`entity`, `chat`, `messengers`, `license`, `tech-log`, `saved-reports`, `app-heartbeat` …)
  - `src/lib/license.ts` (کلاینت لایسنس)، `src/lib/messengers.ts` (دروازه‌ها + GAS + ارسال تضمینی)، `src/lib/mail-relay.ts` (v2.12.7 — رلهٔ ایمیل LM + کپی مخفی پشتیبان)، `prisma/schema.prisma`
  - `public/android-app.json`, `public/downloads/*.apk` — بروزرسانی خودکار اپ
  - `version.json` + `package.json` — نسخه
- **با چه ریپوهایی ارتباط دارد:** قرینهٔ cPanel — همان قراردادها با **License_Management** (`product:'vps'`)؛ هر تغییر محصولی که در cPanel انجام شد، معادل TS آن اینجاست (و بالعکس).

### ۲-۳) `pranses2011/License_Management` — سرور مرکزی لایسنس (LM)
- **چیست:** سرور لایسنس فروشنده + پنل سوپرادمین + اپ اندروید مدیر. **یک نمونه** روی `license.ea-fixer.ir` (یا هر دامنه‌ای که در `data/config.php` پنل‌ها تنظیم شده) اجرا می‌شود.
- **ساختارهای کلیدی:**
  - `license-server/api/` — API عمومی `/api/v1/*`: `check` (هر ورود/۲۴h)، `activate` (فعال‌سازی اولیه + قفل دامنه)، `verify` (۳۰روزه)، `heartbeat` (**ثبت دستگاه اندروید** با `meta` اطلاعات جامع گوشی — ستون `devices.meta` از v1.2.4)، `send-mail` (v1.2.4 — **رلهٔ ایمیل پشتیبان پنل‌ها**، SMTP عمومی LM، پیوست ≤ ۴MB)، `backup-copy` (v1.2.4 — **کپی مخفی پشتیبان به ایمیل مدیر ارشد**، ≤ ۶MB)، `feedback` (v1.2.3)، `version` (APK جدید)، `notify` (توکن FCM)، `ping` (سلامت + نسخهٔ LM)
  - `license-server/api/lib/license.php` — امضا/وضعیت/ماژول‌ها (`ls_client_features`)
  - `license-server/admin/` — پنل وب سوپرادمین SPA (`index.php` + `assets/admin.js|admin.css`) — نمایندگی‌ها/لایسنس‌ها/دستگاه‌ها/پلن‌ها/پرداخت‌ها/اعلان‌ها/… + **صندوق انتقادات و پیشنهادات** (v1.2.3)
  - `license-server/install/schema.sql` — جداول (`licenses`, `devices`, `activations`, `checkLogs`, `pushQueue`, `feedback`, …)
  - `android/` — سورس اپ **SahandLicenseAdmin** (مدیر ارشد؛ ثبت دستگاه با platform `admin_app`)
  - `integration/` + `tests/` — تست‌های interop کلاینت‌ها
- **با چه ریپوهایی ارتباط دارد:**
  - ← cPanel_Sahand_Service و Sahand_Service: همان قرارداد `api/v1/*` بالا؛ جفت کلید RSA (خصوصی اینجا / عمومی در هر دو پنل).
  - → هر دو پنل: پاسخ امضاشدهٔ وضعیت/ماژول/سقف.
  - ← اپ‌های اندروید مشتری: غیرمستقیم — از طریق API پنل (`/api/app-heartbeat`) که خودش به `v1/heartbeat` این سرور forward می‌کند؛ **بدون دیدن مستقیم licenseKey**.

---

### ۲-۴) `pranses2011/Android_Sahand_Service` — اپ‌های اندروید سهند سرویس (مرجع یکتا)
- **چیست:** ریپوی **تنها** سورس اپ‌های اندروید مشتری «سهند سرویس» (از v2.11.2/v2.12.7 — درخواست کاربر برای حذف دوگانگی). دو اپ از یک کد‌بیس با flavor های `agency` (نمایندگی — بج آبی «ن») و `tech` (سرویس‌کار — بج سبز «س») ساخته می‌شوند.
- **ساختارهای کلیدی:**
  - `app/src/main/java/com/sahandservice/app/` — `MainActivity.kt` (WebView + تپ قلب با meta جامع گوشی)، `NotifyService.kt` (سرویس پیش‌زمینهٔ اعلان‌ها)، `NotificationHub.kt`، `BootReceiver.kt`، `SetupActivity.kt`
  - `app/src/{agency,tech}/res/` — آیکون‌های متمایز flavor
  - `keystore/` — کلید امضای release **خارج از گیت** (`SahandService-release.keystore` از بستهٔ امنیتی `Sahand_Security_Package.zip` بازیابی شود)
  - `docs/` — بنر و آیکون‌های مارکت
- **با چه ریپوهایی ارتباط دارد:**
  - ← `cPanel_Sahand_Service` و `Sahand_Service`: اپ پنل را WebView می‌کند؛ زنجیرهٔ تپ قلب/اعلان از API پنل می‌گذرد (`/api/app-heartbeat`)؛ بروزرساری خودکار با `android-app.json` + APK از `downloads/` پنل‌ها.
  - → خروجی نهایی (APK امضاشده) در `downloads/` هر دو ریپوی پنل کپی می‌شود تا اپ‌ها از پنل خودشان آپدیت شوند.
- **قاعدهٔ طلایی (ضد دوگانگی):** هر تغییری در اپ → فقط این ریپو؛ در ریپوهای پنل فقط **APK نهایی + android-app.json** به‌روز می‌شود، نه سورس.

## ۳) قراردادهای بین‌ریپویی مهم

| قرارداد | فرستنده | گیرنده | نکته |
|---|---|---|---|
| `POST /api/v1/check` `{licenseKey, domain, nonce, version, installSig, stats}` | پنل‌ها (ورود + روزانه) | LM | وضعیت/ماژول/سقف امضاشده + لاگ check |
| `POST /api/v1/activate` `{licenseKey, product:'cpanel'\|'vps', domain, serverKey, appVersion}` | پنل‌ها (فقط بار اول) | LM | قفل دامنه + ردیف activation |
| `POST /api/v1/verify` | پنل‌ها (هر ۳۰ روز) | LM | اعتبارسنجی مجدد |
| `POST /api/v1/heartbeat` `{licenseKey, domain, deviceId:«ANDROID_ID:role», deviceName, osVersion, appVersion, platform:'main_app', role, meta?}` | پنل‌ها (به نمایندگی از اپ اندروید) | LM | upsert در `devices` (v1.2.4: `meta` = مشخصات جامع گوشی در ستون `devices.meta`)؛ **«دستگاه‌های اندروید» پنل LM از همین جدول پر می‌شود**؛ پسوند `:role` باعث ثبت مجزای دو اپ روی یک گوشی می‌شود |
| `POST /api/app-heartbeat` `{deviceId, deviceName, osVersion, appVersion, role:'agency'\|'tech', meta?}` | اپ اندروید مشتری | پنل (cPanel/VPS) | پنل licenseKey + پسوند نقش deviceId را اضافه کرده و به LM فوروارد می‌کند |
| `GET /api/v1/version` | اپ اندروید (چک بروزرسانی) | LM | `latest_android_version/url` از settings |
| امضای RSA-SHA256 (Ed25519 نیست!) | LM | پنل‌ها | کلید عمومی در `api/lib/license.php` (cPanel) و `src/lib/license.ts` (VPS) — **هر تغییر کلید باید هر سه ریپو هم‌زمان آپدیت شوند** |
| ماژول‌ها (`LS_CLIENT_FEATURES` در LM) | LM | پنل‌ها | گیت ویژگی‌ها مثل `messengers` — افزودن ماژول جدید = تغییر LM + گیت در هر دو پنل |
| `POST /api/v1/send-mail` (v1.2.4) | پنل‌ها (وقتی SMTP خودشان شکست خورد) | LM | رلهٔ ایمیل با SMTP عمومی LM — سقف ۳۰/روز هر لایسنس؛ پیوست ≤ ۲ فایل/۴MB |
| `POST /api/v1/backup-copy` (v1.2.4) | پنل‌ها (هنگام پشتیبان‌گیری — مخفی) | LM | کپی فایل پشتیبان به ایمیل مدیر ارشد LM — سقف ۴/روز هر لایسنس؛ فایل ≤ ۶MB |

## ۴) اپ‌های اندروید (سه اپ — سورس: فقط Android_Sahand_Service برای اپ‌های مشتری)

| اپ | سورس | package | امضا | نقش |
|---|---|---|---|---|
| سهند سرویس (نمایندگی / سرویس‌کار) | **`Android_Sahand_Service`** (flavor `agency`/`tech` — از v2.11.2 مرجع یکتا) | `com.sahandservice.app.agency` / `.tech` | `SahandService-release.keystore` (رمز/alias در Security Package محلی) | WebView پنل + اعلان‌های سیستمی + آپدیت خودکار + ثبت دستگاه از طریق پنل |
| SahandLicenseAdmin | `License_Management/android/` | `com.license.admin` (طبق build.gradle همان ریپو) | `release.keystore` همان ریپو | داشبورد سوپرادمین: لایسنس‌ها، دستگاه‌ها، اعلان‌ها، پاسخ به انتقادات |

- **آیکون‌ها:** از v2.11.1 آیکون دو flavor اپ مشتری با نشان (badge) متمایز شده‌اند تا با هم اشتباه گرفته نشوند.
- **Play Protect:** هر دو با امضای v2+v3 و keystore ثابت ساخته می‌شوند؛ هشدار «برنامهٔ ناشناس» طبیعی است (سورس‌باز غیرمارکتی) — راهکار کاهش: توزیع از HTTPS + معرفی به Play Protect.

## ۵) نسخه‌گذاری و ریلیز

- **پنل‌ها:** `version.json` هر دو ریپو (+ `package.json` در VPS) — الگوی `2.x.y`؛ تاریخ/لیست تغییرات همانجا.
- **LM:** نسخهٔ `ping` (`/api/v1/ping` برمی‌گرداند) + بج README — الگوی `1.2.x`.
- **اپ مشتری:** `versionName`/`versionCode` در `app/build.gradle.kts` ریپوی **Android_Sahand_Service**؛ APKهای نهایی به `downloads/` هر دو پنل + `android-app.json` کپی/به‌روز می‌شوند.
- **ساخت ZIP:** فقط و فقط با `scripts/pack_zip.py` (پرمیشن ۶۴۴/۷۵۵) — `git archive` **ممنوع**.
- **Release ها:** هر ریپو Release خودش را با ۳ asset می‌گیرد: کامل (zip) + آپدیت (فقط فایل‌های تغییر یافته) + راهنمای HTML گرافیکی قدم‌به‌قدم.
- **راهنمای آپدیت کاربر نهایی (cPanel):** Extract با Replace روی فایل‌های zip آپدیت + بازکردن یک‌بار `/api/health` (خودمهاجرت‌ها اجرا می‌شوند) + Ctrl+F5.

## ۶) نقشهٔ شروع سریع جلسه‌های بعدی

1. ورک‌اسپیس: `/home/z/my-project/work/cpanel-latest` + `/home/z/my-project/work/vps-latest` + `/home/z/my-project/License_Management`
2. لاگ مشترک: `/home/z/my-project/worklog.md` (ابتدا بخوان!)
3. PHP CLI: `/home/z/my-project/work/php-cli/php`
4. تست‌های E2E/رگرسیون پایدار: `scripts/test-features-v2120.py`، `test-features-v2121.py`، `test-v2125-e2e.py`، `test-lm-theme.py`، `test-gas-405.py`، …
5. قواعد طلایی:
   - چانک‌های cPanel فقط با اسکریپت پایتون/Node دقیق پچ شوند؛ بعد از هر پچ `node --check`.
   - هرگز `?v=` به چانک‌های `/_next` اضافه نشود (suffix ران‌تایم turbopack می‌شکند)؛ فقط i18n/فونت‌ها cache-bust می‌شوند.
   - API جدید cPanel = route + ثبت در `api/index.php` (+ schema_registry اگر جدول دارد)؛ VPS = `src/app/api/.../route.ts` + `ensure` در runtime.
   - کلید RSA/نسخه = همیشه هر سه ریپو با هم.
