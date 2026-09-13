add module k2j-cli producing k2j.exe. cli opts be like:

k2j --help
Convert Kotlin source files to Java

Usage: k2j.exe [OPTIONS] [INPUTS]...

Arguments:
  [INPUTS]...

Options:
  -o, --output <OUTPUT>      Directory where converted Java files are written [default: .]
--source Kotlin files or directories to convert

--jar jar containing compiled kotlin
  --progress              Emit conversion progress
      --relax                Allow untranslated Kotlin warnings and continue conversion
      --save <SAVE>          Move successfully converted Kotlin files under this directory (default is --save .k2j-save)
     --delete                  Delete decompiled kotlin files
      --restore [<RESTORE>]  Restore Kotlin files from a previous --save operation (default: --restore .k2j-save)
  -h, --help                 Print help

what's missing?