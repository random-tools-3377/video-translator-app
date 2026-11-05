# Test clips

Public recordings only, for checking that the app's Whisper settings turn Russian and Japanese
speech into sensible English. No private recordings are ever used. CI downloads each file, checks its
sha256 (listed in `clips.tsv`), and cuts the stretch listed there; the files are not committed.

| Clip | What it is | Recording | Licence | Source |
|---|---|---|---|---|
| `en-jfk` | J. F. Kennedy, inaugural address, 1961 (11 s) | US government | Public domain | whisper.cpp `samples/jfk.wav` |
| `ja-udhr` | Universal Declaration of Human Rights, Article 1, in Japanese (20 s) | kaseumin, for LibriVox | Public domain | [Commons](https://commons.wikimedia.org/wiki/File:Universal_Declaration_of_Human_Rights-jpn-Art1.ogg) |
| `ja-botchan` | Natsume Sōseki, *Botchan* (1906), opening of chapter 1 (first 45 s) | marsian | Public domain | [Commons](https://commons.wikimedia.org/wiki/File:Ja-botchan_1-1_1-2.ogg) |
| `ru-chekhov` | Anton Chekhov, *Fat and Thin* (1883), opening (first 40 s) | Pogrebnoj-Alexandroff | [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/) | [Commons](https://commons.wikimedia.org/wiki/File:Ru-%D0%A7%D0%B5%D1%85%D0%BE%D0%B2-%D0%A2%D0%BE%D0%BB%D1%81%D1%82%D1%8B%D0%B9_%D0%B8_%D1%82%D0%BE%D0%BD%D0%BA%D0%B8%D0%B9.ogg) |
| `ru-tolstoy` | Leo Tolstoy, *Childhood* (1852), chapter 2 "Maman" (first 45 s) | Yakovlev Valery and Yakovleva Evgeniya, for LibriVox | Public domain | [Commons](https://commons.wikimedia.org/wiki/File:Leo-Tolstoy-Detstvo-RUSSIAN-02-Maman.mp3) |

The texts themselves are long out of copyright. `ru-chekhov` is used under CC BY 3.0: recording
by Pogrebnoj-Alexandroff, via Wikimedia Commons; it is cut and resampled for the test and not
redistributed. Licences were read from Commons' file metadata on 2026-11-04 by the
"Find test clips" workflow (`.github/workflows/find-test-clips.yml`), which can search for more.

`ja-udhr-transcript.txt` is Whisper large-v3's transcript of `ja-udhr` (two lines of the Japanese
UDHR text, which is public domain), in `tools/vt-translate`'s input format. The ARM-emulator job
feeds it to the language model, so that check does not need Whisper first. The emulator test also
wraps the `ja-udhr` clip in an `.mp4` (`scripts/make-test-video.sh`); it is never committed.
