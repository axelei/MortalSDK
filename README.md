# MortalSDK
Extractor e insertor de bloques comprimidos y textos de Mortal Kombat (Mega Drive). Está escrito en Java 24 y preparado para compilarlo AoT con GraalVM. Puede servir para otros juegos, en particular para los que usan compresión RNC.

¿Por qué en Java? Porque es el lenguaje que, en este momento, me da de comer. :)

## Uso:

### Extracción:

`MortalSDK x "mortal kombat.bin" [configuracion.properties]`

En la carpeta `extracted` se generarán los bloques descomprimidos. Los gráficos están en formato tiles de Mega Drive, es decir, 4bpp linear. El texto estará en el nombre de la ROM más `.txt`. Si se especifica `configuracion.properties` se usará esta.

En la carpeta `configs` hay un ejemplo de configuración que estoy usando para mi proyecto personal.

### Inyección:

`MortalSDK i "mortal kombat.bin" [configuracion.properties]`

Se generará un fichero nuevo con los recursos inyectados. Si se especifica `configuracion.properties` se usará esta.

## Requisitos

Require `rnc_propack_x64.exe` u otra compilación para extraer/inyectar bloques comprimidos RNC. Se puede obtener de: https://github.com/lab313ru/rnc_propack_source/releases

Con pequeños ajustes en la configuración se puede usar con otras ROMs y otros sistemas operativos. Añade un 'issue' si tienes alguna propuesta de cambio.

## Compilación

Para compilarlo, necesitas tener instalado Maven y GraalVM. Puedes encontrar más información en la [página oficial de GraalVM](https://www.graalvm.org/). Si no quieres o no necesitas compilación AoT, elimina dicha sección del `pom.xml`.

Sólo necesitas ejecutar en Windows:

`mvnw.cmd clean package`

En la carpeta `dist` tendrás `MortalSDK.jar`, que incluye sus dependencias. Se ejecuta con:

`java -jar dist/MortalSDK.jar x "mortal kombat.bin" "dist/configs/Mortal Kombat Arcade Edition v2-0.properties"`

## Recursos editables

La extracción conserva siempre los binarios originales y genera representaciones editables adicionales:

- `extracted/data_*.bin`: bloques RNC descomprimidos.
- `extracted/previews/data_*.png`: vistas indexadas de los bloques compatibles con tiles 4bpp de Mega Drive. Cada gris representa un índice de color de 0 a 15; las paletas no forman parte de los tiles.
- `extracted/pcm_*.pcm`: audio PCM crudo.
- `extracted/pcm_*.wav`: audio PCM original signed de 8 bits convertido al WAV estándar de 8 bits, mono y 7040 Hz.
- `extracted/samples/sample_*.wav`: 114 muestras válidas delimitadas por la tabla de audio de la ROM. El nombre conserva ID, offset, longitud y flags. La entrada `0x63` de esta versión apunta fuera de la ROM y se informa, pero no se extrae.
- `extracted/music_*.music`: bancos, tablas y driver Z80 del subsistema musical conservados en formato binario crudo.
- `<rom>.txt`: textos extraídos mediante la tabla TBL correspondiente.

Durante la inyección, los PNG se convierten de nuevo a tiles y los WAV a PCM antes de recomprimir los bloques. MortalSDK rechaza dimensiones, colores, formatos de audio o tamaños que puedan sobrescribir datos adyacentes. La salida se escribe siempre como `<rom>.patched.bin`; la ROM de entrada no se modifica.

Para RNC ProPack v1.8, `rnc_propack_x64.exe` debe estar en el directorio desde el que se ejecuta MortalSDK o `proPackExe` debe indicar su ruta.

## Editor gráfico de samples

En Windows se puede abrir con doble clic en `MortalSDK-GUI.cmd` y escribir la ruta de la ROM, arrastrar la ROM sobre el lanzador, o ejecutarlo desde consola:

`java -jar dist/MortalSDK.jar gui "mortal kombat.bin" "dist/configs/Mortal Kombat Arcade Edition v2-0.properties"`

El editor permite seleccionar y reproducir cada muestra, cargar un WAV sustituto, descartar el cambio y generar una ROM nueva. Los WAV deben ser mono, PCM de 8 bits y usar la frecuencia configurada.

Los samples modificados se escriben en los rangos declarados mediante `spaceRanges`. MortalSDK actualiza automáticamente el puntero de 24 bits y la longitud de 16 bits de cada entrada de la tabla, y finalmente recalcula el checksum. La ROM original no se modifica.

## CLI de samples

La CLI permite inspeccionar y extraer la tabla de samples sin abrir la interfaz gráfica:

`java -jar dist/MortalSDK.jar sample list "mortal kombat.bin" "configuracion.properties"`

`java -jar dist/MortalSDK.jar sample extract "mortal kombat.bin" "samples" "configuracion.properties"`

Para sustituir uno o varios WAV y generar una ROM nueva:

`java -jar dist/MortalSDK.jar sample replace "mortal kombat.bin" "salida.bin" "configuracion.properties" 01 "nuevo-01.wav" 0A "nuevo-0A.wav"`

Los ID se interpretan como hexadecimales. Todos los reemplazos de una ROM deben indicarse en la misma ejecución. Igual que el editor gráfico, el comando comprueba que cada WAV sea PCM unsigned de 8 bits, mono y use la frecuencia configurada; recoloca los datos mediante `spaceRanges`, actualiza punteros y longitudes y repara el checksum. Nunca sobrescribe la ROM de entrada.

## Cosas por hacer (no necesariamente en orden)

- Extraer paletas
- Mejorar la extracción de textos
- Programar mi propio extractor y compresor de RNC para no necesitar rnc_propack_x64.exe
- Internacionalizar los mensajes
- Crear tests unitarios
- Lanzar mejores alertas si hay inconsistencias y recuperación de errores

## Autoría y reconocimientos

Gracias a [Rael G. C.](https://github.com/raelgc) por la información que me faltaba sobre el formato de gráficos y esquema de compresión. 

By Krusher, licenciado bajo GPL 3. Por favor, consulta el fichero LICENSE.
