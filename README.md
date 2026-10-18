# Archivos TV

Gestor de archivos sencillo para Android TV (pensado para la Xiaomi Mi Box S), con estilo tipo webOS y detalles de Xiaomi. Se maneja solo con el control remoto.

## Qué hace

- **Fotos, Audio y Video:** busca en todo el almacenamiento (interno y USB) y muestra solo ese tipo de archivo.
- **Todos:** explorador de carpetas normal. El botón Atrás sube de carpeta.
- Abre los archivos con la app que tengas instalada para ese tipo (por ejemplo VLC).
- Solo lectura: no copia, borra ni renombra.

## Compilar el APK con GitHub Actions

1. Sube los cambios a la rama `main`.
2. Entra a la pestaña **Actions** del repo y abre la ejecución más reciente de **Compilar APK**.
3. Al terminar, descarga el archivo **ArchivosTV-debug-apk** de la sección Artifacts. Es un zip con el APK adentro.

También se puede lanzar a mano desde Actions con **Run workflow**.

## Compilar en tu PC

Abre la carpeta con Android Studio (JDK 17), espera a que sincronice Gradle y usa **Build → Build APK(s)**.

## Instalar en la Mi Box S

- Con ADB: `adb connect IP_DE_LA_TV:5555` y luego `adb install app-debug.apk`.
- O pasa el APK a la TV con la app Send Files to TV y ábrelo desde ahí.

Al abrir la app por primera vez, acepta el permiso de almacenamiento.

## Detalles técnicos

- Kotlin, sin librerías de terceros salvo `androidx.core` y `androidx.recyclerview`.
- `minSdk 23`, `targetSdk 28` (a propósito, para leer todo el almacenamiento sin permisos especiales en Android 8.1/9).
