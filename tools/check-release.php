#!/usr/bin/env php
<?php
/**
 * check-release.php — گارد خط پایهٔ انتشار اپ اندروید (v2.11.4+)
 * منبع نسخه: app/build.gradle.kts (versionName).
 * ریشهٔ حادثهٔ ۲۰۲۶-۰۹-۰۶: رلیز بدون کامیت push → پاک‌شدن تغییرات؛
 * + خطر کلید امضا: همیشه keystore/SahandService-release.keystore رسمی
 *   (Sahand Security Package) — وگرنه آپدیت روی نصب‌ها نمی‌نشیند.
 * اجرا:  php tools/check-release.php   (خروج ۰ = مجاز، ۱ = توقف)
 */

declare(strict_types=1);

$root = dirname(__DIR__);
$gradle = $root . '/app/build.gradle.kts';

function fail(string $msg): void { fwrite(STDERR, "⛔ RELEASE GUARD: {$msg}\n"); exit(1); }

if (!is_file($gradle)) fail('app/build.gradle.kts یافت نشد');
$s = (string) file_get_contents($gradle);
if (!preg_match('/versionName\s*=\s*"([^"]+)"/', $s, $m)) fail('versionName یافت نشد');
$v = $m[1];
preg_match('/versionCode\s*=\s*(\d+)/', $s, $m2);
$code = $m2[1] ?? '?';

$baselineFile = $root . '/release-baseline.json';
if (is_file($baselineFile)) {
    $b = json_decode((string) file_get_contents($baselineFile), true);
    $bl = $b['lastOfficialRelease'] ?? null;
    if ($bl && version_compare($v, $bl) <= 0) {
        fail("نسخهٔ فعلی ({$v}) از آخرین رلیز رسمی ({$bl}) بزرگ‌تر نیست — بیلد متوقف شد.");
    }
    if (isset($b['lastVersionCode']) && (int) $code <= (int) $b['lastVersionCode']) {
        fail("versionCode ({$code}) باید از رلیز قبلی ({$b['lastVersionCode']}) بزرگ‌تر باشد.");
    }
    echo "✓ نسخهٔ {$v} (code {$code}) > رلیز رسمی قبلی {$bl}\n";
}

/* کلید امضای رسمی */
$ks = $root . '/keystore/SahandService-release.keystore';
if (!is_file($ks)) {
    echo "⚠ keystore/SahandService-release.keystore موجود نیست — از Sahand Security Package کپی کنید (بدون آن، آپدیت روی نصب‌های v2.11.x نمی‌نشیند!)\n";
} else {
    echo "✓ کلید امضای رسمی موجود است\n";
}

echo "✅ انتشار مجاز است (نسخه {$v} — code {$code})\n";
exit(0);
