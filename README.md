# MortalSDK
Extractor e insertor de bloques comprimidos y textos de Mortal Kombat (Mega Drive). Está escrito en Java 24 y preparado para compilarlo AoT con GraalVM. Puede servir para otros juegos, en particular para los que usan compresión RNC.

¿Por qué en Java? Porque es el lenguaje que, en este momento, me da de comer. :)

## Uso:

### Extracción:

`MortalSDK x "mortal kombat.bin" [configuracion.properties]`

En la carpeta `extracted` se generarán los bloques descomprimidos. Los gráficos están en formato tiles de Mega Drive, es decir, 4bpp linear. El texto estará en el nombre de la ROM más `.txt`. Si se especifica `configuracion.properties` se usará esta.

En la carpeta `configs` hay un ejemplo de configuración que estoy usando para mi proyecto personal.

Los samples PCM se extraen a WAV. No hay que configurarlos: se busca en la ROM la tabla que los describe y se vuelca cada entrada a `sample_<id>_<direccion>.wav`, con la frecuencia a la que el juego reproduce ese sample.

La tabla son entradas de ocho bytes: identificador (1), dirección del PCM (3), longitud (2) y velocidad de reproducción (2). El PCM es de 8 bits con signo; WAV lo guarda sin signo, así que la conversión es un XOR con `0x80` en los dos sentidos.

### Inyección:

`MortalSDK i "mortal kombat.bin" [configuracion.properties]`

Se generará un fichero nuevo con los recursos inyectados. Si se especifica `configuracion.properties` se usará esta.

Con los samples PCM se sigue este criterio:

- Si se ha borrado su WAV de la carpeta `extracted`, el sample original se queda como está.
- Si el WAV no se ha modificado respecto a la ROM original, tampoco se toca nada.
- Si cabe en su hueco, se escribe ahí y se acorta la longitud de su entrada. No se rellena el sobrante, porque los samples van pegados unos a otros y se pisaría el siguiente.
- Si no cabe, se mueve al espacio libre de `spaceRanges` y se corrigen la dirección y la longitud de su entrada. Si no hay sitio, no se inyecta y se avisa por consola.

Se admite cualquier WAV PCM de 8 o 16 bits, mono o estéreo, y a cualquier frecuencia: no se remuestrea, se escribe en la tabla la velocidad de reproducción más parecida a la del WAV. El máximo que alcanza el reproductor de la ROM son unos 13,8 kHz.

Los bloques de `bins` nunca se reubican ni cambian de tamaño, porque no tienen por qué ser direccionables por puntero.

## Requisitos

Require `rnc_propack_x64.exe` u otra compilación para extraer/inyectar bloques comprimidos RNC. Se puede obtener de: https://github.com/lab313ru/rnc_propack_source/releases

Con pequeños ajustes en la configuración se puede usar con otras ROMs y otros sistemas operativos. Añade un 'issue' si tienes alguna propuesta de cambio.

## Compilación

Para compilarlo, necesitas tener instalado Maven y GraalVM. Puedes encontrar más información en la [página oficial de GraalVM](https://www.graalvm.org/). Si no quieres o no necesitas compilación AoT, elimina dicha sección del `pom.xml`.

Sólo necesitas ejecutar: `mvn clean package`. En la carpeta `dist` tendrás el resultado.

## Cosas por hacer (no necesariamente en orden)

- Extraer paletas
- Mejorar la extracción de textos
- Programar mi propio extractor y compresor de RNC para no necesitar rnc_propack_x64.exe
- Internacionalizar los mensajes
- Crear tests unitarios
- Lanzar mejores alertas si hay inconsistencias y recuperación de errores

## Cambios recientes

- Los samples PCM se extraen a WAV y se reinyectan desde WAV, dentro del mismo flujo `x` / `i`.
- La tabla de samples ya no se configura: se busca dentro de la ROM.
- Cada WAV lleva la frecuencia real a la que el juego reproduce ese sample, deducida del reproductor Z80.
- Al inyectar, un sample que no quepa se mueve al espacio libre y se corrigen su dirección y su longitud.
- Se ha quitado la propiedad `sounds`, que servía para listar los sonidos a mano.

## Autoría y reconocimientos

Gracias a [Rael G. C.](https://github.com/raelgc) por la información que me faltaba sobre el formato de gráficos y esquema de compresión. 

By Krusher, licenciado bajo GPL 3. Por favor, consulta el fichero LICENSE.