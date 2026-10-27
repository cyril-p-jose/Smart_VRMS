# Dot-source from project scripts. Paths contain approved dependencies.
$mavenRepo = Join-Path $env:USERPROFILE '.m2/repository'
$approvedJars = @(
    (Join-Path $mavenRepo 'com/google/code/gson/gson/2.15.0/gson-2.15.0.jar'),
    (Join-Path $mavenRepo 'com/google/errorprone/error_prone_annotations/2.50.0/error_prone_annotations-2.50.0.jar'),
    (Join-Path $mavenRepo 'org/postgresql/postgresql/42.7.14/postgresql-42.7.14.jar'),
    (Join-Path $mavenRepo 'org/checkerframework/checker-qual/3.55.1/checker-qual-3.55.1.jar'),
    (Join-Path $mavenRepo 'org/apache/pdfbox/pdfbox/3.0.3/pdfbox-3.0.3.jar'),
    (Join-Path $mavenRepo 'org/apache/pdfbox/pdfbox-io/3.0.3/pdfbox-io-3.0.3.jar'),
    (Join-Path $mavenRepo 'org/apache/pdfbox/fontbox/3.0.3/fontbox-3.0.3.jar'),
    (Join-Path $mavenRepo 'commons-logging/commons-logging/1.3.3/commons-logging-1.3.3.jar'),
    (Join-Path $mavenRepo 'com/google/zxing/core/3.5.3/core-3.5.3.jar'),
    (Join-Path $mavenRepo 'com/google/zxing/javase/3.5.3/javase-3.5.3.jar'),
    (Join-Path $mavenRepo 'com/beust/jcommander/1.82/jcommander-1.82.jar'),
    (Join-Path $mavenRepo 'com/github/jai-imageio/jai-imageio-core/1.4.0/jai-imageio-core-1.4.0.jar')
)
foreach ($jarPath in $approvedJars) { if (-not (Test-Path -LiteralPath $jarPath)) { throw "An approved dependency is missing from the default Maven cache: $jarPath. Run the approved Maven build." } }
$serverClassPath = (@('contracts/target/classes','application/target/classes','server/target/classes') + $approvedJars) -join ';'
