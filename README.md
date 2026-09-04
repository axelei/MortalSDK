# MortalSDK
Extractor e insertor de bloques comprimidos y textos de Mortal Kombat (Mega Drive). Está escrito en Java 24 y preparado para compilarlo AoT con GraalVM. Puede servir para otros juegos, en particular para los que usan compresión RNC.

¿Por qué en Java? Porque es el lenguaje que, en este momento, me da de comer. :)

## Uso:

### Extracción:

`MortalSDK x "mortal kombat.bin" [configuracion.properties]`

En la carpeta `extracted` se generarán los bloques descomprimidos como PNG, en `data_<direccion>.png`. El texto estará en el nombre de la ROM más `.txt`. Si se especifica `configuracion.properties` se usará esta.

En la carpeta `configs` hay un ejemplo de configuración que estoy usando para mi proyecto personal.

### Gráficos

Los bloques comprimidos se dibujan como hojas de tiles de Mega Drive: 8x8 píxeles de 4 bits, dos píxeles por byte, 32 bytes por tile, 16 tiles por fila. El PNG sale indexado de 4 bits, así que al abrirlo se sigue trabajando con los 16 índices que guarda la ROM.

Lo que se lee del PNG al inyectar son los índices, no los colores. La paleta es sólo para poder ver el dibujo: da igual acertar con ella, la ida y vuelta sale exacta de todos modos. Si el editor ha convertido el PNG a color, se busca para cada píxel el color más parecido de la paleta.

No hace falta que un bloque sea un número redondo de tiles: la última fila se rellena y al volver se recorta al tamaño que tenía en la ROM. Tampoco todos los bloques comprimidos son gráficos; los que no lo son se ven como ruido, pero van y vuelven igual de bien.

El ancho del PNG marca cuántos tiles hay por fila, así que conviene no cambiarlo. Si se cambia, se avisa por consola.

#### Paletas

Una línea de paleta de Mega Drive son 16 palabras big-endian con el formato `0000 BBB0 GGG0 RRR0`. Qué paleta le toca a cada bloque no se puede deducir de la ROM sin los mapas de tiles, así que por defecto se usa una rampa de grises, que deja los 16 índices bien distinguibles.

Para ver un bloque con sus colores de verdad se indica a mano en la configuración, con direcciones en hexadecimal:

```properties
palettes=1959bc,19597a
```

La configuración incluida trae ese caso, que es el único confirmado: la paleta de `0x19597A` da los colores de escenario del bloque `0x1959BC`.

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

Ninguno aparte de Java: la compresión RNC ProPack va incluida, así que ya no hace falta `rnc_propack_x64.exe` ni ningún otro programa externo.

Compilado con GraalVM sale un único `.exe`, sin ninguna DLL al lado. Los WAV se leen y se escriben a mano en vez de con `javax.sound`, que vive en el módulo java.desktop y arrastra código nativo: usándolo, la compilación nativa dejaba un `jsound.dll` junto al ejecutable que había que repartir con él.

Con pequeños ajustes en la configuración se puede usar con otras ROMs y otros sistemas operativos. Añade un 'issue' si tienes alguna propuesta de cambio.

### Sobre la compresión RNC

Están los dos métodos, el 1 (Huffman + LZ77) y el 2. Los bloques se buscan por toda la ROM, se descomprimen y, al inyectar, se vuelven a comprimir.

Los dos métodos dan exactamente los mismos bytes que la herramienta original: comprobado con los 141 bloques de la ROM de Mortal Kombat, en los dos sentidos y con los dos métodos. Recomprimir esos bloques devuelve además los bytes que ya estaban en la ROM.

Cuando unos datos no se pueden comprimir, la herramienta original dice que ha ido bien pero deja un fichero que ni ella misma es capaz de descomprimir. Aquí eso se detecta y se avisa en vez de escribir un bloque roto.

## Compilación

Para compilarlo, necesitas tener instalado Maven y GraalVM. Puedes encontrar más información en la [página oficial de GraalVM](https://www.graalvm.org/). Si no quieres o no necesitas compilación AoT, elimina dicha sección del `pom.xml`.

Sólo necesitas ejecutar: `mvn clean package`. En la carpeta `dist` tendrás el resultado.

## Cosas por hacer (no necesariamente en orden)

- Mejorar la extracción de textos
- Internacionalizar los mensajes
- Crear tests unitarios
- Lanzar mejores alertas si hay inconsistencias y recuperación de errores

## Cambios recientes

- Los gráficos se extraen y se inyectan como PNG en vez de como volcados de tiles. Ya no se generan los `.bin`.
- Se pueden asignar paletas de la ROM a los bloques para verlos con sus colores.
- Los WAV se leen y se escriben sin `javax.sound`, así que la compilación nativa ya no genera `jsound.dll` y el ejecutable adelgaza unos 2 MB.
- El método 2 de RNC ya da los mismos bytes que la herramienta original (antes salía alguno más largo).
- `dist` ya no se queda con el ejecutable de la compilación anterior.
- La compresión RNC ProPack ya va dentro del programa: se acabó depender de `rnc_propack_x64.exe`.
- Ya no se genera `extracted/log.txt`: los tamaños originales se sacan de la propia ROM al inyectar.
- Se ha quitado la propiedad `proPackExe`.
- Los samples PCM se extraen a WAV y se reinyectan desde WAV, dentro del mismo flujo `x` / `i`.
- La tabla de samples ya no se configura: se busca dentro de la ROM.
- Cada WAV lleva la frecuencia real a la que el juego reproduce ese sample, deducida del reproductor Z80.
- Al inyectar, un sample que no quepa se mueve al espacio libre y se corrigen su dirección y su longitud.
- Se ha quitado la propiedad `sounds`, que servía para listar los sonidos a mano.

## Autoría y reconocimientos

Gracias a [Rael G. C.](https://github.com/raelgc) por la información que me faltaba sobre el formato de gráficos y esquema de compresión.

La compresión RNC ProPack está portada del código que publicó [Lab 313 (Dr. MefistO)](https://github.com/lab313ru/rnc_propack_source), a su vez sacado de la herramienta original de Rob Northen Computing.


By Krusher, licenciado bajo GPL 3. Por favor, consulta el fichero LICENSE.