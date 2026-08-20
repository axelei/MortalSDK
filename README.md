# MortalSDK

Extractor e inyector de recursos para *Mortal Kombat* de Mega Drive. Trabaja con bloques RNC, textos, tiles 4bpp y audio PCM. El comportamiento específico de cada ROM se describe mediante un fichero `.properties`; el repositorio incluye una configuración para *Mortal Kombat Arcade Edition v2.0*.

MortalSDK nunca necesita modificar la ROM de entrada: los comandos de inyección generan un fichero nuevo.

## Requisitos

- Java 24 para ejecutar el JAR o compilar el proyecto.
- RNC ProPack para extraer e inyectar bloques comprimidos. Puede obtenerse en [rnc_propack_source](https://github.com/lab313ru/rnc_propack_source/releases).
- GraalVM solamente si se quiere generar el ejecutable AOT opcional.

`rnc_propack_x64.exe` debe estar en el directorio de ejecución o debe indicarse su ruta mediante `proPackExe` en la configuración.

## Compilación

En Windows:

```powershell
mvnw.cmd clean package
```

En Linux o macOS:

```sh
./mvnw clean package
```

El resultado autocontenido es `dist/MortalSDK.jar`. Para solicitar además la compilación AOT con una instalación de GraalVM que incluya Native Image:

```powershell
mvnw.cmd -Pnative clean package
```

El perfil AOT original se conserva. La CLI no depende de la interfaz gráfica; la GUI debe validarse con los metadatos AWT y Java Sound correspondientes a la versión de GraalVM utilizada.

## Extracción e inyección completa

```powershell
java -jar dist/MortalSDK.jar x "juego.bin" "dist/configs/Mortal Kombat Arcade Edition v2-0.properties"
java -jar dist/MortalSDK.jar i "juego.bin" "dist/configs/Mortal Kombat Arcade Edition v2-0.properties"
```

Los modos `x` e `i` aceptan una configuración opcional. Si se omite, solo se aplican los valores predeterminados y no estarán disponibles los rangos específicos del juego.

La extracción crea `extracted/` y `<rom>.txt`. La inyección lee esos recursos y escribe `<rom>.patched.bin`. Para comprobar el flujo antes de editar nada, se recomienda extraer e inyectar sin cambios y comparar ambos ficheros mediante SHA-256.

## Recursos generados

| Recurso | Contenido | Condiciones de reinyección |
| --- | --- | --- |
| `extracted/data_*.bin` | Bloques RNC descomprimidos | Se recomprimen con RNC método 1 |
| `extracted/previews/data_*.png` | Vista editable de tiles compatibles con 4bpp | Dimensiones y valores de índice válidos |
| `extracted/palettes/palette_*.pal` | Candidatos de paleta CRAM referenciados | 16 colores, 32 bytes |
| `extracted/pcm_*.pcm` | Regiones PCM crudas | Longitud original exacta |
| `extracted/pcm_*.wav` | Las mismas regiones en WAV reproducible | Mono, PCM unsigned de 8 bits, frecuencia configurada y longitud exacta |
| `extracted/samples/sample_*.wav` | Samples delimitados por la tabla de audio | En modo `i`, longitud original exacta |
| `extracted/music_*.music` | Bancos, tablas o driver de música en crudo | Longitud original exacta |
| `<rom>.txt` | Textos encontrados mediante la tabla TBL | Se reutilizan huecos configurados cuando es posible |

Los PNG son imágenes de índices, no gráficos con la paleta real del juego: cada nivel de gris representa un valor de 0 a 15. Los ficheros `.music` se conservan como binarios porque todavía no existe un editor de secuencias musicales.

## Localización de paletas

```powershell
java -jar dist/MortalSDK.jar palette scan "juego.bin" "paletas"
java -jar dist/MortalSDK.jar palette render "juego.bin" 0x19597A "extracted/data_1959bc.bin" "preview.png"
java -jar dist/MortalSDK.jar palette report "juego.bin" "extracted" "palette-report.html"
```

`palette scan` busca direcciones de ROM referenciadas por punteros y cuyos 32 bytes cumplen el formato CRAM de Mega Drive. Exporta el binario, una muestra PNG, una hoja conjunta y un CSV con las referencias. Es un detector de candidatos: una coincidencia debe confirmarse mediante la tabla o rutina que la usa.

`palette render` aplica un candidato a un bloque lineal de tiles para comprobarlo visualmente. No interpreta mapas de tiles ni el selector de línea de paleta de sus atributos, por lo que un bloque que use varias líneas CRAM no se verá completamente correcto con una sola paleta.

`palette report` genera un HTML en cuadrícula con todas las tiras de color, referencias y previews. Para cada paleta usa el bloque 4bpp extraído cuya dirección es más cercana y muestra la distancia, por lo que sirve para inspección rápida pero no confirma por sí solo la asociación.

Los hallazgos confirmados para Arcade Edition v2.0 están explicados en [docs/palettes.md](docs/palettes.md).

## Samples desde la CLI

Consultar la tabla:

```powershell
java -jar dist/MortalSDK.jar sample list "juego.bin" "configuracion.properties"
```

Extraer únicamente sus WAV:

```powershell
java -jar dist/MortalSDK.jar sample extract "juego.bin" "directorio-samples" "configuracion.properties"
```

Reemplazar uno o varios samples, permitiendo cambiar su duración:

```powershell
java -jar dist/MortalSDK.jar sample replace "juego.bin" "salida.bin" "configuracion.properties" 01 "nuevo-01.wav" 0A "nuevo-0A.wav"
```

Los ID se interpretan como hexadecimales. Los WAV deben ser mono, PCM unsigned de 8 bits y utilizar `pcmSampleRate`. El comando:

1. valida todos los WAV y los rangos libres;
2. coloca los samples por orden de ID y alineados a palabra;
3. actualiza sus punteros de 24 bits y longitudes de 16 bits;
4. repara el checksum de Mega Drive;
5. escribe una ROM distinta sin sobrescribir la entrada.

Todos los reemplazos deben realizarse juntos partiendo de la misma ROM base. El asignador no mantiene un registro persistente de ocupación: volver a usar una ROM ya parcheada con el mismo `spaceRanges` podría reutilizar espacio ocupado anteriormente.

Más detalles sobre el formato y sus límites en [docs/audio-samples.md](docs/audio-samples.md).

## Editor gráfico de samples

```powershell
java -jar dist/MortalSDK.jar gui "juego.bin" "configuracion.properties"
```

En Windows también puede usarse `MortalSDK-GUI.cmd`: acepta la ROM arrastrada como primer argumento o solicita su ruta. La GUI permite escuchar, preparar varios reemplazos, restablecer cambios pendientes y generar una ROM nueva. Utiliza exactamente el mismo servicio de validación y recolocación que la CLI.

## Configuración

Los rangos son inclusivos y se escriben en decimal como `inicio,fin`; varios rangos se separan con `#`. `sampleTableOffset` también acepta la notación hexadecimal `0x...`.

| Propiedad | Uso |
| --- | --- |
| `minChars` | Longitud mínima de los textos detectados |
| `textRanges` | Zonas donde buscar textos |
| `sounds` | Regiones PCM crudas |
| `music` | Regiones crudas del subsistema musical |
| `bins` | Otros bloques sin comprimir |
| `spaceRanges` | Huecos confirmados como libres para recolocación |
| `proPackExe` | Ruta del ejecutable RNC ProPack |
| `pcmSampleRate` | Frecuencia utilizada al importar, exportar y reproducir WAV |
| `sampleTableOffset` | Dirección de la tabla de samples |
| `sampleCount` | Número de entradas de ocho bytes de la tabla |

`spaceRanges` es una declaración de confianza: MortalSDK comprueba sus límites y solapamientos, pero no puede demostrar que el juego no use esos bytes. Debe confirmarse para cada revisión concreta de la ROM.

## Estado de la configuración Arcade Edition v2.0

- Tabla principal: `0x281C50`.
- Entradas configuradas: 116; 114 tienen datos válidos dentro de la ROM.
- Formato observado: ID de 8 bits, offset de 24 bits, longitud de 16 bits y flags de 16 bits, todo en big-endian.
- `pcmSampleRate=7040` es una frecuencia provisional de escucha/importación basada en las pruebas actuales, no una medición definitiva del temporizador de reproducción del juego.
- La entrada con ID `0x63` contiene un offset fuera de una ROM de 4 MiB. MortalSDK la informa y la omite; no intenta corregirla sin confirmar primero su comportamiento en ejecución.

La configuración identifica una revisión de ROM por su estructura, pero MortalSDK todavía no impone un hash concreto. Verifique la revisión antes de inyectar.

## Seguridad y copias

- Conserve siempre una ROM limpia fuera del directorio de trabajo.
- No declare un rango como libre solo porque contenga muchos `00` o `FF`.
- Pruebe la ROM generada en un emulador y en hardware cuando corresponda.
- No distribuya ROMs ni recursos del juego; el repositorio contiene únicamente herramientas y configuración.

## Trabajo pendiente

- Asociar cada paleta confirmada con sus tiles y mapas.
- Decodificar y editar secuencias musicales.
- Confirmar la frecuencia PCM desde el código del driver.
- Resolver la semántica de la entrada de sample `0x63`.
- Sustituir la dependencia externa de RNC ProPack.
- Internacionalizar los mensajes y mejorar la recuperación ante errores.

## Autoría y reconocimientos

Gracias a [Rael G. C.](https://github.com/raelgc) por la información sobre el formato gráfico y el esquema de compresión.

MortalSDK fue creado por Krusher y se distribuye bajo GPL 3. Consulte [LICENSE](LICENSE).
