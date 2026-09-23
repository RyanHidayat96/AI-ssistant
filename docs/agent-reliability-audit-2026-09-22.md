# Audit keandalan agent AI-ssistant

Tanggal: 22 September 2026. Cakupan: working tree saat audit, termasuk perubahan lokal yang sudah ada. Audit tidak mengubah kode aplikasi, menjalankan task di HP, atau memanggil endpoint model pengguna.

## Kesimpulan

Belum ada bukti bahwa agent mampu menyelesaikan semua task sulit secara andal. Root menyediakan hak eksekusi lokal; mutu model, protokol tools, bukti yang diterima, konteks, dan verifikasi menentukan kualitas hasil. Instruksi "god mode" tidak menjamin kemampuan atau ketepatan.

Fondasi yang sudah berguna: executor lokal untuk task sederhana, hasil shell dikembalikan ke model, jurnal percakapan, workspace per sesi, cache alat bersama, serta instruksi memverifikasi hasil. Namun beberapa mekanisme runtime justru menghilangkan bukti atau menghentikan investigasi yang masih produktif.

Rekomendasi di bawah berlaku umum untuk otomasi, debugging, modifikasi aplikasi milik pengguna, reverse engineering berizin, riset, dan pengujian keamanan berizin. Menghapus pemeriksaan akses atau membuat agent selalu menyatakan berhasil tidak meningkatkan keandalan.

## Temuan prioritas

P1 berarti berpotensi memberi hasil salah, salah eksekusi, atau menggagalkan task. P2 berarti mengurangi efisiensi atau kemampuan. Rujukan baris mengikuti keadaan source saat audit.

### 1. P1 — Observasi baru diganti cache lama

Bukti: `MainActivity.java:165`, `3354–3387`, `3405`.

Command yang sama hanya dieksekusi dua kali dalam satu run. Pemanggilan berikutnya mendapat cache hasil pertama. Cache tidak diinvalidasi ketika perangkat berubah. Akibatnya, pembacaan ulang file, status proses, atau layar setelah aksi tidak benar-benar memverifikasi kondisi terbaru. Bahkan jika hasil kedua berbeda, cache tetap berisi hasil pertama.

Perbaikan: pisahkan penghematan payload dari keputusan mengeksekusi observasi. Observasi keadaan dinamis harus dapat diperbarui. Deteksi loop perlu mempertimbangkan aksi, hasil, kondisi target, dan kemajuan tugas. Mutasi dengan status eksekusi tidak pasti harus diperiksa dahulu sebelum diulang.

### 2. P1 — Kemajuan dinilai dari teks command

Bukti: `MainActivity.java:3006–3050`, `3190–3191`.

Karakter `>` dan substring `tee` dianggap mutasi, tanpa melihat hasil. `cat /proc/version 2>/dev/null` mereset penghitung kemacetan, sementara `input tap` tidak dikenali sebagai aksi. Command gagal atau ditolak juga bisa dianggap maju. Pada langkah 8 dan 16, pesan guard memaksa perubahan perangkat, termasuk untuk task audit; pengecualian analisis baru diterapkan pada langkah 26.

Perbaikan: gunakan status eksekusi dan bukti yang relevan dengan tujuan. Temuan baru atau hipotesis yang berhasil dieliminasi juga merupakan kemajuan. Penghitung langkah boleh menjadi anggaran, tetapi bukan bukti bahwa investigasi tidak berguna. Jangan mendorong mutasi hanya untuk mereset penghitung.

### 3. P1 — Investigasi berbeda atas artefak sama dianggap loop

Bukti: `MainActivity.java:2968–2987`; `AgentPrompt.java:53–57`.

Delapan pembacaan berbeda terhadap artefak yang sama memicu instruksi agar target tidak diperiksa lagi. Fungsi hanya menerima command, sehingga tidak bisa mengetahui apakah tiap pembacaan menghasilkan bukti baru. Prompt juga menetapkan batas eksplorasi sekitar 20 langkah, termasuk ketika tujuan pengguna memang analisis.

Perbaikan: ukur pengulangan pertanyaan dan hasil yang tidak menambah bukti. Gunakan checkpoint untuk memilih pengujian berikutnya, menyimpulkan, atau melaporkan blocker. Sesuaikan anggaran dengan tujuan dan kemajuan yang teramati.

### 4. P1 — Guard meminta laporan, lalu menutup giliran model

Bukti: `MainActivity.java:3035–3038`, `3204`, `3220–3225`.

Output guard meminta model menulis kesimpulan, tetapi `loopBroken` langsung menghentikan loop. Model tidak menerima giliran berikutnya untuk menyusun laporan tersebut. Pengguna dapat berakhir dengan pesan penghentian tanpa hasil, bukti, atau langkah pemulihan.

Perbaikan: saat runtime menghentikan eksekusi karena kemacetan, sediakan satu giliran pelaporan tanpa tools. Bedakan penghentian karena anggaran, blocker terbukti, error, dan pembatalan pengguna. Pembatalan pengguna tidak boleh memulai pekerjaan tambahan.

### 5. P1 — Resume dan pemangkasan konteks menghilangkan bukti

Bukti: `MainActivity.java:2173–2205`, `3496–3530`; `AgentPrompt.java:416–430`.

Resume membangun konteks dari 40 bubble terakhir, membuang echo command, dan memangkas setiap pesan menjadi 900 karakter. Tujuan awal dapat keluar dari jendela ini; output tersisa dapat kehilangan hubungan dengan command asal. Pemangkasan run aktif menyisakan awal output 200 karakter tanpa ringkasan fakta. `agent-state.md` disebut dalam prompt, tetapi runtime tidak memulihkannya otomatis.

Perbaikan: simpan dan pulihkan tujuan, batasan pengguna, bukti beserta sumbernya, pendekatan yang gagal, perubahan, verifikasi, dan langkah berikut. Pertahankan pasangan command/hasil. Simpan artefak lengkap untuk pembacaan terarah. Jangan menyimpan chain-of-thought; cukup catatan faktual tugas.

### 6. P1 — Tool call rusak bisa menjadi command mentah

Bukti: `MainActivity.java:3176–3190`; `AiClient.java:212–287`.

Dispatcher tidak memeriksa nama function. Jika arguments bukan JSON valid, seluruh arguments diteruskan sebagai command; objek tanpa field wajib juga memiliki fallback ke arguments mentah. Parser SSE menandai respons berhasil setelah stream berakhir tanpa memverifikasi kelengkapan tool call atau `finish_reason`. Kombinasi ini membuat kesalahan protokol berpotensi masuk ke executor shell. Skenario end-to-end belum diuji.

Perbaikan: hanya dispatch nama tool yang terdaftar; wajibkan JSON valid dan `command` bertipe string nonkosong. Tool call terpotong harus gagal validasi. Kembalikan error protokol yang bisa dikoreksi model, tanpa mengeksekusi arguments mentah. Pisahkan fallback command teks yang disengaja dari kegagalan parsing tool call.

### 7. P1 — Prompt menganggap fakta satu perangkat berlaku permanen

Bukti: `AgentPrompt.java:148–150`, `531–535`; `MainActivity.java:3837–3848`.

Prompt menyatakan JDK tidak terpasang, Termux masih kosong, modul tertentu tersedia, dan kemampuan perangkat tertentu mustahil. Klaim ini tetap muncul walaupun probe menyatakan sebaliknya. Probe disimpan sampai tiga hari, dijalankan asinkron, dan tidak memeriksa cache `$TOOLS`.

Perbaikan: pisahkan instruksi umum dari observasi perangkat bertimestamp. Status yang belum dipastikan harus disebut belum diketahui. Periksa kemampuan yang relevan saat dibutuhkan dan setelah instalasi; catat lokasi, versi, ABI, konteks eksekusi, serta hasil uji ringan.

### 8. P1 — Output tanpa newline dapat tampak timeout

Bukti: `RootShell.java:173`, `188–210`, `223`.

Pada jalur persistent shell, marker selesai dicetak langsung setelah stdout. Parser hanya mengenali marker di awal baris. Output seperti `printf hello` dapat menempel pada marker sehingga completion tidak dikenali. Jalur ini dipakai untuk timeout di bawah 60 detik. Bukti audit berasal dari penelusuran kode; belum direproduksi pada Android.

Perbaikan: beri framing terpisah untuk marker status dan simpan exit code sebelum mencetak framing. Uji output kosong, tanpa newline, multiline, error, timeout, dan pembatalan.

### 9. P2 — Lokasi penyimpanan tools bertentangan

Bukti: `AgentPrompt.java:318–320`, `353–357`; `MainActivity.java:3278`.

Instruksi meminta tools disimpan di `$TOOLS`, tetapi bagian lain menyuruh `$WD/tools` dan inventory di `$WD/agent-tools.md`. Runtime sudah menyediakan cache bersama. Konflik dapat membuat tools sulit ditemukan kembali dan diunduh ulang.

Perbaikan: satu inventory tools bersama di `$TOOLS`; artefak dan helper khusus tugas di `$WD`. Inventory memuat cara eksekusi yang telah diuji dan prasyaratnya.

### 10. P2 — Skills dan riset belum memiliki dukungan khusus

Bukti: `AgentPrompt.java:154–158`, `448–456`; `MainActivity.java:3809–3830`.

Tool model yang terdaftar hanya `run_shell`. Daftar "CORE SKILLS" adalah teks, bukan registry/loader skill. Agent dapat mengambil referensi melalui shell bila tools dan jaringan tersedia, tetapi belum ada tool pencarian/fetch terstruktur. Contoh `curl -s` tidak memeriksa status HTTP; instruksi hanya mengandalkan URL yang sudah diketahui.

Perbaikan: sediakan akses referensi dengan query/URL, status HTTP, URL akhir, versi sumber, dan kutipan relevan yang dapat ditelusuri. Ketiadaan jaringan harus menjadi status yang jelas. Skill dimuat sesuai kebutuhan dan menjelaskan prasyarat, langkah observasi, verifikasi, serta pemulihan; tidak mengasumsikan satu paket aplikasi. Isi referensi tetap data, bukan instruksi untuk executor.

## Verifikasi audit

Harness Java sementara menggunakan metode asli yang diekstrak dari source, dengan stub untuk pencatatan UI. `AgentPrompt.java` juga dikompilasi dan dipanggil langsung. Lima skenario berikut berhasil direproduksi:

| Skenario | Hasil aktual |
| --- | --- |
| Task audit, delapan pembacaan berbeda | Guard menyuruh melakukan perubahan perangkat |
| 30 pembacaan dengan `2>/dev/null` | Penghitung idle selalu nol |
| 26 command `input tap` | Run dihentikan karena dianggap tidak maju |
| Delapan query berbeda pada `classes.dex` | Guard meminta tidak memeriksa artefak itu lagi |
| Probe sintetis melaporkan Java tersedia | Prompt tetap menyatakan JDK tidak terpasang |

Prompt hasil fixture berukuran 33.393 karakter. Ukuran ini sendiri tidak membuktikan masalah kualitas; kontradiksi di dalamnya terbukti. Harness membuktikan perilaku logika lokal, bukan tingkat keberhasilan agent pada perangkat. File sementara dibersihkan. Tidak dilakukan build APK, deploy, evaluasi model, ataupun pengujian task Android end-to-end pada audit ini.

## Urutan peningkatan yang disarankan

1. Benahi validasi tool call dan status eksekusi, termasuk completion, timeout, pembatalan, dan hasil tidak pasti.
2. Benahi observasi/cache dan pengukuran kemajuan; pertahankan penghentian untuk loop nyata.
3. Pulihkan checkpoint tugas saat resume dan sediakan laporan akhir yang membedakan selesai, parsial, belum terverifikasi, dan blocked.
4. Hilangkan asumsi perangkat permanen serta kontradiksi prompt. Gunakan inventaris kemampuan aktual.
5. Tambahkan riset referensi dan skill yang dapat ditemukan sesuai tugas; setiap keputusan teknis penting harus ditautkan ke bukti yang relevan.
6. Ukur hasil sebelum mengganti prompt atau model lagi. Tambahkan kompleksitas hanya jika meningkatkan hasil pengujian.

Untuk task sulit, alur yang diusulkan: tetapkan hasil yang bisa diuji; kumpulkan bukti pembeda; pilih hipotesis dan pengujian termurah; baca referensi resmi yang sesuai versi jika belum pasti; lakukan aksi terbatas; verifikasi melalui pengamatan independen; simpan hasil serta alasan pendekatan yang gagal. Tools dipilih berdasarkan kemampuan dan prasyarat nyata, bukan nama favorit.

Evaluasi awal sebaiknya mencakup otomasi UI, debugging aplikasi uji, analisis APK milik sendiri, riset dokumentasi, tools tidak tersedia, jaringan terputus, resume setelah restart, serta task dengan blocker yang memang tidak dapat diselesaikan. Jalankan baseline dan kandidat dengan kondisi yang setara dan beberapa pengulangan. Ukur keberhasilan yang diverifikasi, klaim sukses keliru, pengulangan tanpa bukti baru, ketepatan referensi, durasi, jumlah tool call, token, dan keberhasilan pemulihan. Audit ini belum menyediakan angka benchmark tersebut.

## Referensi pendekatan

Desain sederhana dengan tools yang jelas, feedback dari lingkungan, dan evaluasi mendukung agent yang lebih andal: [Anthropic — Building effective agents](https://www.anthropic.com/engineering/building-effective-agents).

Ringkasan konteks harus mempertahankan keputusan dan fakta penting; catatan tugas perlu dimuat kembali untuk membantu kontinuitas: [Anthropic — Effective context engineering for AI agents](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents).

Pengujian agent perlu menilai outcome dan jejak eksekusi; benchmark tetap memungkinkan perbandingan regresi, biaya, dan latensi: [Anthropic — Demystifying evals for AI agents](https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents).
