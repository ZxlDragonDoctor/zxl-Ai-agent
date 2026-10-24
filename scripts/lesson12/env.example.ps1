# Example only. Copy to ignored target/lesson12-db-env.ps1 and edit your own local copy.
# Never put real credentials in this tracked file.
$env:LESSON12_DB_URL = 'jdbc:postgresql://127.0.0.1:15432/lesson12?connectTimeout=5&socketTimeout=10'
$env:LESSON12_DB_USER = 'lesson12_owner'
# Read securely instead of committing or displaying a database password.
$lesson12DbSecret = Read-Host 'Local PostgreSQL password' -AsSecureString
$env:LESSON12_DB_PASSWORD = [System.Net.NetworkCredential]::new('', $lesson12DbSecret).Password
Remove-Variable lesson12DbSecret

$env:LESSON12_PORT = '18132'
$env:LESSON12_EMBEDDING_MODE = 'demo' # Offline hash coordinates, NOT semantic embeddings.

# Real semantic embedding: uncomment and supply your own region/deployment endpoint.
# No provider request happens merely by setting these variables or starting the server.
# Import and search requests DO call the provider and can incur charges.
# $env:LESSON12_EMBEDDING_MODE = 'http'
# $env:LESSON12_EMBEDDING_ENDPOINT = 'https://YOUR-REGIONAL-ENDPOINT/compatible-mode/v1/embeddings'
# $env:LESSON12_EMBEDDING_MODEL = 'text-embedding-v3'
# $env:LESSON12_EMBEDDING_REVISION = 'your-deployment-revision'
# $env:LESSON12_EMBEDDING_DIMENSIONS = '1024'
# $lesson12ApiSecret = Read-Host 'Embedding API key' -AsSecureString
# $env:LESSON12_EMBEDDING_API_KEY = [System.Net.NetworkCredential]::new('', $lesson12ApiSecret).Password
# Remove-Variable lesson12ApiSecret
