$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
mvn -o -B verify
if ($LASTEXITCODE -ne 0) { throw 'Maven verification failed.' }
. (Join-Path $PSScriptRoot 'server-classpath.ps1')
$classPath = "contracts/target/classes;application/target/classes;desktop/target/classes;$($approvedJars[0]);$($approvedJars[1])"
New-Item -ItemType Directory -Force verification/generated | Out-Null
javac --release 17 -encoding UTF-8 -cp $classPath -d verification/generated verification/FoundationChecks.java
if ($LASTEXITCODE -ne 0) { throw 'Foundation check compilation failed.' }
java '-Djava.awt.headless=true' -cp "$classPath;verification/generated" FoundationChecks
if ($LASTEXITCODE -ne 0) { throw 'Foundation checks failed.' }
javac --release 17 -encoding UTF-8 -cp 'server/target/classes' -d verification/generated verification/SecurityPolicyChecks.java
if ($LASTEXITCODE -ne 0) { throw 'Security policy check compilation failed.' }
java -cp 'server/target/classes;verification/generated' in.smartvrms.server.security.SecurityPolicyChecks
if ($LASTEXITCODE -ne 0) { throw 'Security policy checks failed.' }
. (Join-Path $PSScriptRoot 'server-classpath.ps1')
javac --release 17 -encoding UTF-8 -cp $serverClassPath -d verification/generated verification/AuthAdapterChecks.java
if ($LASTEXITCODE -ne 0) { throw 'Auth adapter check compilation failed.' }
java -cp "$serverClassPath;verification/generated" in.smartvrms.server.integration.AuthAdapterChecks
if ($LASTEXITCODE -ne 0) { throw 'Auth adapter checks failed.' }
javac --release 17 -encoding UTF-8 -cp $serverClassPath -d verification/generated verification/DatabaseSetupChecks.java
if ($LASTEXITCODE -ne 0) { throw 'Database setup check compilation failed.' }
java -cp "$serverClassPath;verification/generated" DatabaseSetupChecks
if ($LASTEXITCODE -ne 0) { throw 'Database setup checks failed.' }
javac --release 17 -encoding UTF-8 -cp $serverClassPath -d verification/generated verification/StaffAuthorizerChecks.java
if ($LASTEXITCODE -ne 0) { throw 'Staff authorizer check compilation failed.' }
java -cp "$serverClassPath;verification/generated" in.smartvrms.server.security.StaffAuthorizerChecks
if ($LASTEXITCODE -ne 0) { throw 'Staff authorizer checks failed.' }
javac --release 17 -encoding UTF-8 -cp $serverClassPath -d verification/generated verification/JdbcStaffContextChecks.java
if ($LASTEXITCODE -ne 0) { throw 'Transaction-context checks did not compile.' }
java -cp "$serverClassPath;verification/generated" in.smartvrms.server.security.JdbcStaffContextChecks
if ($LASTEXITCODE -ne 0) { throw 'Transaction-context checks failed.' }
$prototypeClassPath = "$serverClassPath;desktop/target/classes"
foreach ($prototypeCheck in @('PrototypeSessionChecks','AtomicFleetWriteChecks')) {
    javac --release 17 -encoding UTF-8 -cp $prototypeClassPath -d verification/generated "verification/$prototypeCheck.java"
    if ($LASTEXITCODE -ne 0) { throw 'Prototype check compilation failed.' }
}
java -cp "$prototypeClassPath;verification/generated" in.smartvrms.desktop.integration.PrototypeSessionChecks
if ($LASTEXITCODE -ne 0) { throw 'Prototype session checks failed.' }
java -cp "$prototypeClassPath;verification/generated" in.smartvrms.verification.AtomicFleetWriteChecks
if ($LASTEXITCODE -ne 0) { throw 'Atomic fleet write checks failed.' }
javac --release 17 -encoding UTF-8 -cp $prototypeClassPath -d verification/generated verification/LiveFleetProtocolChecks.java
if ($LASTEXITCODE -ne 0) { throw 'Live fleet protocol check compilation failed.' }
java '-Djava.awt.headless=true' -cp "$prototypeClassPath;verification/generated" in.smartvrms.verification.LiveFleetProtocolChecks
if ($LASTEXITCODE -ne 0) { throw 'Live fleet protocol checks failed.' }
javac --release 17 -encoding UTF-8 -cp $serverClassPath -d verification/generated verification/RuntimePasswordChecks.java
if ($LASTEXITCODE -ne 0) { throw 'Runtime password check compilation failed.' }
java -cp "$serverClassPath;verification/generated" RuntimePasswordChecks
if ($LASTEXITCODE -ne 0) { throw 'Runtime password checks failed.' }
$reviewClassPath = "$serverClassPath;desktop/target/classes"
javac --release 17 -encoding UTF-8 -cp $reviewClassPath -d verification/generated verification/ReviewRegressionChecks.java
if ($LASTEXITCODE -ne 0) { throw 'Independent review checks did not compile.' }
java '-Djava.awt.headless=true' -cp "$reviewClassPath;verification/generated" in.smartvrms.verification.ReviewRegressionChecks
if ($LASTEXITCODE -ne 0) { throw 'Independent review checks failed.' }
$allClassPath = "$serverClassPath;desktop/target/classes"
javac --release 17 -encoding UTF-8 -cp $allClassPath -d verification/generated verification/Stage1SecurityAndAuthBoundaryChecks.java
if ($LASTEXITCODE -ne 0) { throw 'Stage 1 security and auth check compilation failed.' }
java '-Djava.awt.headless=true' -cp "$allClassPath;verification/generated" in.smartvrms.server.security.Stage1SecurityAndAuthBoundaryChecks
if ($LASTEXITCODE -ne 0) { throw 'Stage 1 security and auth checks failed.' }
javac --release 17 -encoding UTF-8 -cp $allClassPath -d verification/generated verification/Stage2PersonnelAndDriverChecks.java
if ($LASTEXITCODE -ne 0) { throw 'Stage 2 personnel and driver check compilation failed.' }
java '-Djava.awt.headless=true' -cp "$allClassPath;verification/generated" in.smartvrms.server.security.Stage2PersonnelAndDriverChecks
if ($LASTEXITCODE -ne 0) { throw 'Stage 2 personnel and driver checks failed.' }
javac --release 17 -encoding UTF-8 -cp $allClassPath -d verification/generated verification/Stage2AIntegrationChecks.java
if ($LASTEXITCODE -ne 0) { throw 'Stage 2A check compilation failed.' }
java '-Djava.awt.headless=true' -cp "$allClassPath;verification/generated" in.smartvrms.verification.Stage2AIntegrationChecks
if ($LASTEXITCODE -ne 0) { throw 'Stage 2A checks failed.' }
javac --release 17 -encoding UTF-8 -cp $allClassPath -d verification/generated verification/Stage2BIntegrationChecks.java
if ($LASTEXITCODE -ne 0) { throw 'Stage 2B check compilation failed.' }
java '-Djava.awt.headless=true' -cp "$allClassPath;verification/generated" in.smartvrms.verification.Stage2BIntegrationChecks
if ($LASTEXITCODE -ne 0) { throw 'Stage 2B checks failed.' }
javac --release 17 -encoding UTF-8 -cp $allClassPath -d verification/generated verification/Stage3IntegrationChecks.java
if ($LASTEXITCODE -ne 0) { throw 'Stage 3 check compilation failed.' }
java '-Djava.awt.headless=true' -cp "$allClassPath;verification/generated" in.smartvrms.verification.Stage3IntegrationChecks
if ($LASTEXITCODE -ne 0) { throw 'Stage 3 checks failed.' }
javac --release 17 -encoding UTF-8 -cp $allClassPath -d verification/generated verification/Stage3FleetAndDocumentChecks.java
if ($LASTEXITCODE -ne 0) { throw 'Stage 3 fleet and document check compilation failed.' }
java '-Djava.awt.headless=true' -cp "$allClassPath;verification/generated" in.smartvrms.verification.Stage3FleetAndDocumentChecks
if ($LASTEXITCODE -ne 0) { throw 'Stage 3 fleet and document checks failed.' }
javac --release 17 -encoding UTF-8 -cp $allClassPath -d verification/generated verification/Stage4IntegrationChecks.java
if ($LASTEXITCODE -ne 0) { throw 'Stage 4 check compilation failed.' }
java '-Djava.awt.headless=true' -cp "$allClassPath;verification/generated" in.smartvrms.verification.Stage4IntegrationChecks
if ($LASTEXITCODE -ne 0) { throw 'Stage 4 checks failed.' }
javac --release 17 -encoding UTF-8 -cp $allClassPath -d verification/generated verification/Stage4BookingAndCustomerChecks.java
if ($LASTEXITCODE -ne 0) { throw 'Stage 4 booking and customer check compilation failed.' }
java '-Djava.awt.headless=true' -cp "$allClassPath;verification/generated" in.smartvrms.verification.Stage4BookingAndCustomerChecks
if ($LASTEXITCODE -ne 0) { throw 'Stage 4 booking and customer checks failed.' }
javac --release 17 -encoding UTF-8 -cp $allClassPath -d verification/generated verification/Stage5IntegrationChecks.java
if ($LASTEXITCODE -ne 0) { throw 'Stage 5 check compilation failed.' }
java '-Djava.awt.headless=true' -cp "$allClassPath;verification/generated" in.smartvrms.verification.Stage5IntegrationChecks
if ($LASTEXITCODE -ne 0) { throw 'Stage 5 checks failed.' }
javac --release 17 -encoding UTF-8 -cp $allClassPath -d verification/generated verification/Stage5OperationsAndCustodyChecks.java
if ($LASTEXITCODE -ne 0) { throw 'Stage 5 operations and custody check compilation failed.' }
java '-Djava.awt.headless=true' -cp "$allClassPath;verification/generated" in.smartvrms.verification.Stage5OperationsAndCustodyChecks
if ($LASTEXITCODE -ne 0) { throw 'Stage 5 operations and custody checks failed.' }
javac --release 17 -encoding UTF-8 -cp $allClassPath -d verification/generated verification/Stage6IntegrationChecks.java
if ($LASTEXITCODE -ne 0) { throw 'Stage 6 check compilation failed.' }
java '-Djava.awt.headless=true' -cp "$allClassPath;verification/generated" in.smartvrms.verification.Stage6IntegrationChecks
if ($LASTEXITCODE -ne 0) { throw 'Stage 6 checks failed.' }
javac --release 17 -encoding UTF-8 -cp $allClassPath -d verification/generated verification/Stage6FinanceAndLedgerChecks.java
if ($LASTEXITCODE -ne 0) { throw 'Stage 6 finance and ledger check compilation failed.' }
java '-Djava.awt.headless=true' -cp "$allClassPath;verification/generated" in.smartvrms.verification.Stage6FinanceAndLedgerChecks
if ($LASTEXITCODE -ne 0) { throw 'Stage 6 finance and ledger checks failed.' }
javac --release 17 -encoding UTF-8 -cp $allClassPath -d verification/generated verification/Stage7IntegrationChecks.java
if ($LASTEXITCODE -ne 0) { throw 'Stage 7 check compilation failed.' }
java '-Djava.awt.headless=true' -cp "$allClassPath;verification/generated" in.smartvrms.verification.Stage7IntegrationChecks
if ($LASTEXITCODE -ne 0) { throw 'Stage 7 checks failed.' }
javac --release 17 -encoding UTF-8 -cp $allClassPath -d verification/generated verification/Stage7AutomationAndReportingChecks.java
if ($LASTEXITCODE -ne 0) { throw 'Stage 7 automation and reporting check compilation failed.' }
java '-Djava.awt.headless=true' -cp "$allClassPath;verification/generated" in.smartvrms.verification.Stage7AutomationAndReportingChecks
if ($LASTEXITCODE -ne 0) { throw 'Stage 7 automation and reporting checks failed.' }
javac --release 17 -encoding UTF-8 -cp $allClassPath -d verification/generated verification/Stage8HardeningAndJourneysChecks.java
if ($LASTEXITCODE -ne 0) { throw 'Stage 8 check compilation failed.' }
java '-Djava.awt.headless=true' -cp "$allClassPath;verification/generated" in.smartvrms.verification.Stage8HardeningAndJourneysChecks
if ($LASTEXITCODE -ne 0) { throw 'Stage 8 checks failed.' }
javac --release 17 -encoding UTF-8 -cp $allClassPath -d verification/generated verification/Stage9PackagingAndHostingChecks.java
if ($LASTEXITCODE -ne 0) { throw 'Stage 9 check compilation failed.' }
java '-Djava.awt.headless=true' -cp "$allClassPath;verification/generated" in.smartvrms.verification.Stage9PackagingAndHostingChecks
if ($LASTEXITCODE -ne 0) { throw 'Stage 9 checks failed.' }
javac --release 17 -encoding UTF-8 -cp $allClassPath -d verification/generated verification/Stage10ReleaseAndHandoverChecks.java
if ($LASTEXITCODE -ne 0) { throw 'Stage 10 check compilation failed.' }
java '-Djava.awt.headless=true' -cp "$allClassPath;verification/generated" in.smartvrms.verification.Stage10ReleaseAndHandoverChecks
if ($LASTEXITCODE -ne 0) { throw 'Stage 10 checks failed.' }
javac --release 17 -encoding UTF-8 -cp $allClassPath -d verification/generated verification/Stage11DistributionAndReleaseChecks.java
if ($LASTEXITCODE -ne 0) { throw 'Stage 11 check compilation failed.' }
java '-Djava.awt.headless=true' -cp "$allClassPath;verification/generated" in.smartvrms.verification.Stage11DistributionAndReleaseChecks
if ($LASTEXITCODE -ne 0) { throw 'Stage 11 checks failed.' }
