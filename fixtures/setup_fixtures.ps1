$ErrorActionPreference = 'Stop'

# Resolve fixtures directory dynamically relative to this script location
$baseDir = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }
if (-not $baseDir) {
    $baseDir = Join-Path (Get-Location) "fixtures"
}
if (-not (Test-Path $baseDir)) {
    New-Item -ItemType Directory -Path $baseDir | Out-Null
}

Write-Host "Creating Fixtures in $baseDir..."

# Helper function to init git repo
function Init-Repo($repoPath) {
    if (Test-Path $repoPath) {
        Remove-Item -Recurse -Force $repoPath
    }
    New-Item -ItemType Directory -Path $repoPath | Out-Null
    Push-Location $repoPath
    & git init --initial-branch=main
    & git config user.name "TraceBack Test"
    & git config user.email "test@traceback.local"
    Pop-Location
}

# ==========================================
# 1. Fixture A: Java/Spring Hikari Failure
# ==========================================
$repoA = Join-Path $baseDir "synthetic-java-spring"
Init-Repo $repoA
Push-Location $repoA

# Commit 1: Initial commit
New-Item -ItemType Directory -Path "src/main/java/com/example/orders" -Force | Out-Null
New-Item -ItemType Directory -Path "src/main/resources" -Force | Out-Null

@"
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>order-service</artifactId>
  <version>1.0.0</version>
</project>
"@ | Set-Content "pom.xml"

@"
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      connection-timeout: 30000
      minimum-idle: 5
server:
  port: 8081
"@ | Set-Content "src/main/resources/application.yml"

@"
package com.example.orders;

public class OrderService {
    public void createOrder(String orderId) {
        System.out.println("Processing order: " + orderId);
    }
}
"@ | Set-Content "src/main/java/com/example/orders/OrderService.java"

& git add .
& git commit -m "feat: initial commit with standard Hikari connection pool configuration"

# Commit 2: Restrict Hikari pool size and connection timeout
Start-Sleep -Milliseconds 500
@"
spring:
  datasource:
    hikari:
      maximum-pool-size: 2
      connection-timeout: 2000
      minimum-idle: 1
server:
  port: 8081
"@ | Set-Content "src/main/resources/application.yml"

& git add src/main/resources/application.yml
& git commit -m "perf(db): optimize connection pool parameters for low memory"

Pop-Location
Write-Host "Fixture A created: synthetic-java-spring"


# ==========================================
# 2. Fixture B: Node.js/Express Failure
# ==========================================
$repoB = Join-Path $baseDir "synthetic-node-express"
Init-Repo $repoB
Push-Location $repoB

# Commit 1: Initial baseline Express app
New-Item -ItemType Directory -Path "routes" -Force | Out-Null

@"
{
  "name": "user-service",
  "version": "1.0.0",
  "main": "server.js",
  "dependencies": {
    "express": "^4.18.2"
  }
}
"@ | Set-Content "package.json"

@"
const express = require('express');
const app = express();
const usersRouter = require('./routes/users');

app.use(express.json());
app.use('/users', usersRouter);

app.listen(3000, () => console.log('Server running on port 3000'));
"@ | Set-Content "server.js"

@"
const express = require('express');
const router = express.Router();

const users = {
  '1': { id: '1', name: 'Alice', profile: { details: { role: 'admin' } } }
};

router.get('/:id', (req, res) => {
  const user = users[req.params.id];
  if (!user) return res.status(404).send('Not found');
  res.json({ id: user.id, name: user.name });
});

module.exports = router;
"@ | Set-Content "routes/users.js"

& git add .
& git commit -m "feat: initial express user service"

# Commit 2: Introduce unhandled TypeError in routes/users.js
Start-Sleep -Milliseconds 500
@"
const express = require('express');
const router = express.Router();

const users = {
  '1': { id: '1', name: 'Alice', profile: { details: { role: 'admin' } } },
  '2': { id: '2', name: 'Bob' } // Missing profile object
};

router.get('/:id', (req, res) => {
  const user = users[req.params.id];
  if (!user) return res.status(404).send('Not found');
  res.json({ id: user.id, name: user.name });
});

router.get('/:id/profile', (req, res) => {
  const user = users[req.params.id];
  // Line 18: Unhandled TypeError if profile is undefined!
  const role = user.profile.details.role;
  res.json({ id: user.id, role: role });
});

module.exports = router;
"@ | Set-Content "routes/users.js"

& git add routes/users.js
& git commit -m "feat(users): add user profile endpoint with nested role access"

Pop-Location
Write-Host "Fixture B created: synthetic-node-express"


# ==========================================
# 3. Fixture C: Working-Tree Missing Files
# ==========================================
$repoC = Join-Path $baseDir "synthetic-working-tree-missing"
Init-Repo $repoC
Push-Location $repoC

New-Item -ItemType Directory -Path "models" -Force | Out-Null

@"
# Application entry point
print('Forensics target app initialized')
"@ | Set-Content "app.py"

@"
{
  "service": "forensics-demo",
  "database": "sqlite:///app.db"
}
"@ | Set-Content "config.json"

@"
class User:
    def __init__(self, username):
        self.username = username
"@ | Set-Content "models/user.py"

@"
def helper():
    return 'healthy'
"@ | Set-Content "utils.py"

@"
DATABASE_MOCK_STORAGE_FILE
"@ | Set-Content "db.sqlite"

& git add .
& git commit -m "feat: initial commit with 5 core project files"

# Now physically remove 2 files from disk without committing or staging
Remove-Item "config.json" -Force
Remove-Item "models/user.py" -Force

Pop-Location
Write-Host "Fixture C created: synthetic-working-tree-missing (2 files deleted from disk)"


# ==========================================
# 4. Fixture D: Honest Abstention (Clean Repo)
# ==========================================
$repoD = Join-Path $baseDir "synthetic-clean-repo"
Init-Repo $repoD
Push-Location $repoD

@"
# Documentation Repository
This repository contains architectural and user documentation for corporate services.
"@ | Set-Content "README.md"

New-Item -ItemType Directory -Path "docs" -Force | Out-Null

@"
# Architecture Overview
System uses cloud-native microservices with standard observability.
"@ | Set-Content "docs/architecture.md"

@"
# API Guidelines
All endpoints follow RESTful conventions and JSON schema validation.
"@ | Set-Content "docs/api.md"

& git add .
& git commit -m "docs: initial architectural documentation"

Start-Sleep -Milliseconds 500
@"
# API Guidelines
All endpoints follow RESTful conventions and JSON schema validation.
Updated authentication header guidelines for RFC-6750.
"@ | Set-Content "docs/api.md"

& git add docs/api.md
& git commit -m "docs(api): update authentication header documentation"

Pop-Location
Write-Host "Fixture D created: synthetic-clean-repo"


# ==========================================
# 5. Fixture E: Misleading Recent Commit
# ==========================================
$repoE = Join-Path $baseDir "synthetic-misleading-commit"
Init-Repo $repoE
Push-Location $repoE

New-Item -ItemType Directory -Path "src/main/resources/static/css" -Force | Out-Null
New-Item -ItemType Directory -Path "src/main/java/com/example/inventory" -Force | Out-Null

@"
spring.datasource.hikari.maximum-pool-size=20
spring.datasource.hikari.connection-timeout=30000
"@ | Set-Content "src/main/resources/application.properties"

@"
package com.example.inventory;

public class InventoryService {
    public boolean checkStock(String sku) {
        return true;
    }
}
"@ | Set-Content "src/main/java/com/example/inventory/InventoryService.java"

@"
.btn-primary {
  background-color: #2563eb;
  color: #ffffff;
}
"@ | Set-Content "src/main/resources/static/css/theme.css"

& git add .
& git commit -m "feat: initial inventory service and assets"

# Commit 1 (older, relevant cause): reduce connection pool
Start-Sleep -Milliseconds 500
@"
spring.datasource.hikari.maximum-pool-size=2
spring.datasource.hikari.connection-timeout=1500
"@ | Set-Content "src/main/resources/application.properties"

& git add src/main/resources/application.properties
& git commit -m "refactor(config): severely restrict connection pool size and timeout"

# Commit 2 (recent, misleading cosmetic): change CSS color
Start-Sleep -Milliseconds 500
@"
.btn-primary {
  background-color: #3b82f6; /* updated brand color */
  color: #ffffff;
  border-radius: 4px;
}
"@ | Set-Content "src/main/resources/static/css/theme.css"

& git add src/main/resources/static/css/theme.css
& git commit -m "style(ui): update primary button brand color and border radius"

Pop-Location
Write-Host "Fixture E created: synthetic-misleading-commit"

Write-Host "All 5 fixtures successfully created under $baseDir."
