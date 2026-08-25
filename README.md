# Depo Yönetim Sistemi

Depo Yönetim Sistemi, ürün, kategori, stok hareketi ve işlem geçmişini tek bir arayüzden yönetmek için geliştirilmiş full-stack bir envanter uygulamasıdır. XLSX, CSV, PDF ve DOCX belgelerinden toplu ürün/stok içe aktarma; ön izleme, doğrulama ve güvenli onay akışı ile desteklenir.

> Bu projede gerçek bağlantı bilgileri ve gizli değerler repoya dahil edilmemiştir.

## Öne çıkan özellikler

- Ürün, kategori ve stok hareketi yönetimi
- Stok giriş/çıkış işlemleri, toplu hareketler ve işlem geçmişi
- Excel ve PDF stok raporlarını dışa aktarma
- XLSX, CSV, PDF ve DOCX ile toplu içe aktarma
- Çok sayfalı Excel çalışma kitaplarını işleme
- İçe aktarma ön izlemesi, satır doğrulama, manuel tamamlama ve iptal akışı
- Aynı belge içindeki kanonik ürün adı varyantlarını güvenli biçimde birleştirme
- Mevcut ürünlerde kaynak-duyarlı eşleştirme ve belirsiz kayıtlar için manuel inceleme
- Fiziksel kaynak satırı kimliği ile miktarın yalnızca bir kez katkı sağlamasını koruma
- Local Ollama ile belge ayrıştırma (`qwen2.5:3b`)
- JWT tabanlı kimlik doğrulama ve `USER` / `ADMIN` rol yetkilendirmesi

## Teknoloji yığını

| Katman | Teknolojiler |
| --- | --- |
| Backend | Java 17, Spring Boot 3.3.2, Spring Web, Spring Security, JWT, Spring Data JPA / Hibernate, MySQL |
| Frontend | React 19, Vite, Tailwind CSS |
| Belge işleme | Apache POI, Apache Tika, Apache PDFBox, Mammoth, SheetJS (`xlsx`) |
| Yerel AI | Ollama, `qwen2.5:3b` |
| Test | JUnit 5, Mockito, Spring Test, Node test runner |

## Mimari

```text
React + Vite + Tailwind
        │ REST / JWT
        ▼
Spring Boot API
 ├─ Controllers
 ├─ Services
 ├─ Security (JWT + roller)
 ├─ Bulk Import Pipeline
 │   ├─ Belge/chunk oluşturma
 │   ├─ Yerel Ollama ayrıştırması
 │   ├─ Kaynak satırı uzlaştırma
 │   ├─ Ön izleme ve doğrulama
 │   └─ Güvenli onay
 └─ JPA / Hibernate
        │
        ▼
      MySQL
```

## Toplu içe aktarma akışı

1. Kullanıcı XLSX, CSV, PDF veya DOCX dosyasını yükler.
2. Belge, yapısına göre güvenli parçalara ayrılır; Excel çalışma sayfaları ayrı ayrı ele alınır.
3. Yerel Ollama ayrıştırması ürün adı, miktar ve ilgili alanlar için yapılandırılmış sonuç üretir.
4. Sonuçlar fiziksel kaynak satırlarıyla uzlaştırılır. Güvenilir biçimde eşleşmeyen kayıtlar otomatik stok değişikliği yapmaz ve incelemeye yönlendirilir.
5. Aynı içe aktarma içindeki kanonik olarak eşdeğer ürünler, katkı yapan fiziksel satır kimlikleri korunarak birleştirilir.
6. Ön izleme, ürünün yeni mi mevcut mu olduğunu veya inceleme gerektirip gerektirmediğini gösterir.
7. Onay anında backend eşleştirmeyi yeniden doğrular; istemciden gelen miktara güvenmez ve miktarı yalnızca bir kez uygular.

Kaynak seçimi eşleştirmenin parçasıdır: aynı kanonik ürün adı farklı kaynakta bulunduğunda kayıt sessizce başka kaynağa bağlanmaz; gerekli ise kullanıcı incelemesi istenir.

## Hızlı Başlangıç

1. Repoyu klonlayın ve proje dizinine geçin:

   ```bash
   git clone <repository-url>
   cd depo-yonetim
   ```

2. Yerel MySQL sunucunuzda boş bir `depo_yonetim` veritabanı oluşturun.
3. Örnek ayar dosyasını kopyalayın:

   Windows PowerShell:

   ```powershell
   Copy-Item env.properties.example env.properties
   ```

   Linux/macOS:

   ```bash
   cp env.properties.example env.properties
   ```

4. `env.properties` içindeki MySQL kullanıcı adı/parolasını kendi yerel bilgilerinizle doldurun.
5. Backend'i başlatın: `mvn spring-boot:run`
6. Ayrı bir terminalde frontend'i başlatın: `cd frontend`, ardından `npm install` ve `npm run dev`.
7. Belge ayrıştırma özelliğini deneyecekseniz isteğe bağlı olarak Ollama'yı başlatın.

## Kurulum

### Gereksinimler

- Java 17
- Maven 3.9+
- Node.js 20+ ve npm
- MySQL 8+
- Toplu belge ayrıştırması için isteğe bağlı Ollama

### Veritabanını Oluşturma

Yerel MySQL sunucunuzda aşağıdaki komutu çalıştırın:

```sql
CREATE DATABASE depo_yonetim
CHARACTER SET utf8mb4
COLLATE utf8mb4_unicode_ci;
```

Uygulama, mevcut Hibernate ayarındaki `spring.jpa.hibernate.ddl-auto=update` sayesinde boş veritabanına bağlandıktan sonra tabloları otomatik olarak oluşturur veya günceller.

### Ortam Değişkenleri

Örnek ayarı kopyalayın:

Windows PowerShell:

```powershell
Copy-Item env.properties.example env.properties
```

Linux/macOS:

```bash
cp env.properties.example env.properties
```

Ardından `env.properties` içindeki yerel değerleri düzenleyin. Örneğin:

```properties
spring.profiles.active=local
DB_URL=jdbc:mysql://localhost:3306/depo_yonetim?useSSL=false&serverTimezone=UTC
DB_HOST=localhost
DB_PORT=3306
DB_NAME=depo_yonetim
DB_USERNAME=root
DB_PASSWORD=kendi_mysql_sifreniz
JWT_SECRET=kendi_uzun_rastgele_secretiniz
DB_SSL_MODE=DISABLED
```

Bu değerler yalnızca örnektir: repoyu klonlayan kişi **kendi yerel MySQL kullanıcı adı ve parolasını** kullanmalıdır. Aiven veya bu projenin geliştirme veritabanı erişimine gerek yoktur. `env.properties` `.gitignore` ile korunur.

| Değişken | Açıklama |
| --- | --- |
| `DB_URL` | MySQL JDBC bağlantı adresi |
| `DB_HOST`, `DB_PORT`, `DB_NAME` | MySQL sunucu ve veritabanı bilgileri |
| `DB_USERNAME`, `DB_PASSWORD` | Yerel veritabanı kullanıcısı ve parolası |
| `JWT_SECRET` | Güçlü, rastgele üretilmiş JWT imzalama anahtarı |
| `DB_SSL_MODE` | MySQL SSL modu |
| `MYSQLDUMP_PATH` | İsteğe bağlı MySQL dump aracının yolu |
| `OLLAMA_TIMEOUT_SECONDS` | Ollama istek zaman aşımı |
| `OLLAMA_CHUNK_SIZE_ROWS` | Yapılandırılmış Excel parça boyutu |
| `OLLAMA_PARALLEL_REQUESTS` | Eşzamanlı yerel ayrıştırma isteği sayısı |
| `OLLAMA_TEXT_CHUNK_MAX_CHARS` | Metin belge parça karakter limiti |
| `OLLAMA_PDF_TEXT_CHUNK_MAX_CHARS` | PDF metin parça karakter limiti |
| `OLLAMA_PDF_RECORDS_PER_CHUNK` | Güvenilir PDF kayıt bloklarındaki kayıt sayısı |
| `OLLAMA_MAX_RETRIES` | Ayrıştırma tekrar deneme sayısı |
| `OLLAMA_STRUCTURED_OUTPUT_ENABLED` | Yapılandırılmış Ollama çıktısını etkinleştirir |

Örnek dosyadaki yer tutucuları gerçek değerlerle yalnızca yerel `env.properties` dosyanızda değiştirin. Hiçbir parola, JWT anahtarı veya veritabanı adresini commit etmeyin.

### Backend'i Çalıştırma

MySQL'de yapılandırdığınız veritabanının erişilebilir olduğundan emin olun, ardından proje kökünde:

```bash
mvn spring-boot:run
```

Varsayılan API adresi: `http://localhost:8080`

### Frontend'i Çalıştırma

Yeni bir terminalde:

```bash
cd frontend
npm install
npm run dev
```

Vite terminalde yerel geliştirme adresini gösterir.

## İlk Giriş

Yerel `local` profilinde `DataInitializer` tarafından oluşturulan demo hesaplarıyla giriş yapabilirsiniz:

| Rol | Kullanıcı adı | Parola |
| --- | --- | --- |
| USER | `demo-user-01` | `demo-password-01` |
| ADMIN | `demo-user-06` | `demo-password-06` |

Bu hesaplar yalnızca yerel demo kurulumu içindir; üretimde kullanılmamalıdır.

## Ollama Kurulumu

Toplu belge ayrıştırma özelliği yerel Ollama servisini kullanır. Uygulamanın diğer envanter işlevleri için zorunlu değildir; ancak belgeyi ayrıştırmak için Ollama çalışıyor olmalıdır.

```bash
ollama pull qwen2.5:3b
ollama serve
```

Varsayılan Ollama adresi `http://localhost:11434`, model ise `qwen2.5:3b` olarak yapılandırılmıştır. Servis erişilemezse içe aktarma işlemi kısmi stok kaydı oluşturmadan hata verir.

## Testler

Backend testleri:

```bash
mvn test
```

Frontend testleri ve kalite kontrolleri:

```bash
cd frontend
npm test
npm run lint
npm run build
```

Belge ayrıştırma testleri mock edilmiş HTTP yanıtları kullanır; test çalıştırmak canlı Ollama isteği gerektirmez.

## Güvenlik Notları

- Tüm API endpoint'leri, giriş endpoint'i dışında JWT ile korunur.
- `USER` ve `ADMIN` rolleri uygulanır; kullanıcı yönetimi ve toplu işlem geri alma gibi yönetim işlemleri ADMIN yetkisi gerektirir.
- Toplu içe aktarma onayı backend tarafında tekrar doğrulanır. İstemci ön izlemesi stok güncellemesi için güven kaynağı değildir.
- Uygulamanın public sürümünde veritabanını sıfırlayan development endpoint'i bulunmaz.
- `env.properties`, `.env` dosyaları, yerel sertifikalar, dump'lar, log'lar ve gerçek içe aktarma belgeleri Git tarafından ignore edilir.


