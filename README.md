# Borderland Jack (plugin Paper 1.21.1)

Game sosial deduksi: satu pemain adalah **Jack** (impostor). Semua pemain punya logo kartu
(Hati ♥, Wajik ♦, Keriting ♣, Sekop ♠) di punggung yang **tidak bisa dilihat pemiliknya sendiri**, kecuali Jack.

## Cara build jadi file .jar
Pilih salah satu:
1. **GitHub (tanpa install apa pun)**: buat repo baru, upload isi folder ini, buka tab *Actions* →
   *Build plugin* → *Run workflow*. Download hasilnya di bagian *Artifacts* (`BorderlandJack.jar`).
2. **Di komputer**: install JDK 21 dan Maven, lalu jalankan `mvn package`.
   Hasilnya ada di `target/BorderlandJack.jar`.

## Pasang di server
- Server harus **Paper 1.21.1** (bukan Fabric/Forge). Pemain cukup Minecraft Java 1.21.1 biasa.
- Taruh `BorderlandJack.jar` di folder `plugins/`, restart server.
- Difficulty **easy/normal** (jangan peaceful, supaya lapar berjalan).

## Setup map (jalankan sebagai admin/OP)
| Perintah | Fungsi |
|---|---|
| `/bl setarena` | Titik awal tiap putaran (berdiri di situ lalu ketik) |
| `/bl addcell` | **Sel voting satu pemain**: berdiri di dalam sel, tatap kotak tebakan, ketik. Ulangi sebanyak jumlah pemain (min. sama dengan jumlah pemain online) |
| `/bl clearcells` | Hapus semua sel |
| `/bl setlobby` | Titik kembali setelah game selesai |
| `/bl addfood` | Tatap meja/furniture makanan lalu ketik (ulangi tiap tempat). Papan **KAFETARIA 78/80** muncul di atasnya |
| `/bl clearfood` | Hapus semua tempat makan |
| `/bl start` / `/bl stop` | Mulai / hentikan game |
| *(mode lama)* `/bl setvotebox` + `/bl setvoteroom` | Satu ruang voting bersama, dipakai kalau sel kurang |

## Cara main
1. Semua pemain online ikut. Satu orang acak jadi Jack. Tiap pemain dapat sel (nomor ditampilkan di chat).
2. **Fase berkeliling** (HUD: `Ronde 1 | 5:16 | 8/10`, angka terakhir = jumlah voting skip):
   tanya simbol temanmu, ambil makanan di kafetaria (klik kanan, stok bersama 80, tampil `Ambil Steak. Sisa makanan 78/80.`).
   Lapar berkurang saat berjalan. Mau skip? **Masuk ke selmu sendiri lalu klik kotak** -> `X telah voting skip. (6/10)`.
   Kalau semua skip, voting langsung mulai (atur di `skip.required-ratio`).
3. **Fase voting**: semua dipindah ke sel masing-masing. Klik kotak -> GUI **TEBAK SIMBOL DI PUNGGUNGMU**:
   pilih Hati / Wajik / Keriting / Sekop -> **Simpan Tebakan** (bisa diganti selama waktu masih ada) -> **Tutup**.
   HUD: `Ronde 1 | 0:35 | Tebakan: Wajik ♦`. Ada tombol *Tuduh Jack* (opsional, perlu suara mayoritas).
4. **Hasil**: benar = title **SELAMAT** + `Benar! Simbolmu memang Wajik ♦`.
   Salah = `Salah! Simbolmu sebenarnya Sekop ♠`, `X tereliminasi.`, mayat + darah di sel, jadi penonton.
5. Pemain menang kalau Jack tereliminasi. Jack menang kalau sisa pemain biasa <= 1 (`config.yml`).

## Skill Jack (tidak butuh mod, memakai tombol bawaan)
- **Mabuk**: tatap pemain, tekan **F** (atau tombol angka **8**). Korban tidak diberi notifikasi,
  tapi melihat logo pemain lain yang salah selama 90 detik.
- **Racun**: dekati tempat makan, tekan tombol angka **9**. Siapa pun (selain Jack) yang makan
  dari situ jadi lebih cepat lapar tanpa notifikasi.
Pemain biasa menekan tombol yang sama tidak terjadi apa-apa.

## Belum ada di versi ini
- Ikon kartu asli di GUI dan layar/papan daftar pemain (butuh resource pack + model custom). GUI memakai wool berwarna dulu.
- Pintu sel dan efek TV di kepala pemain.

## Penting: belum diuji di server
Kode ditulis tanpa bisa dikompilasi dan dites di sini. Kemungkinan perlu penyesuaian kecil:
- Logo terlihat terbalik/menghadap depan → ubah `logo.yaw-offset` (180 ↔ 0) di `config.yml`.
- Logo terlalu tinggi/rendah → ubah `logo.y-offset`.
- Kalau ada error compile, kirim teks errornya ke Claude untuk diperbaiki.

## Batasan
- Server tidak bisa memblokir F5, freelook, atau freecam di sisi pemain. Yang dilakukan plugin:
  logo disembunyikan dari pemiliknya, jadi mengintip lewat F5 tidak memberi apa-apa.
- Logo berupa teks (♠♣♥♦) di entity, bukan model custom. Resource pack bisa ditambahkan nanti.
- Logo mengikuti arah pandang kepala, bukan arah badan.
