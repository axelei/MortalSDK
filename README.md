# MortalSDK
Extractor e insertor de bloques comprimidos y textos de Mortal Kombat (Mega Drive). Está escrito en Java 24 y preparado para compilarlo AoT con GraalVM. Puede servir para otros juegos, en particular para los que usan compresión RNC.

¿Por qué en Java? Porque es el lenguaje que, en este momento, me da de comer. :)

## Uso:

### Extracción:

`MortalSDK x "mortal kombat.bin" [configuracion.properties]`

En la carpeta `extracted` se generarán los bloques descomprimidos como PNG, en `data_<direccion>.png`. El texto estará en el nombre de la ROM más `.txt`. Si se especifica `configuracion.properties` se usará esta.

En la carpeta `configs` hay un ejemplo de configuración que estoy usando para mi proyecto personal.

### Textos

Cada texto ocupa una línea del fichero `.txt`, con el formato `direccion#tamaño#texto#puntero`. La dirección y el puntero van en hexadecimal; el tamaño en decimal, que es el número que importa al traducir: los caracteres que caben.

El puntero lleva delante de qué tipo es, porque no se rellenan igual:

- `abs:01663b` es un puntero absoluto de tres bytes. Si el texto no cabe se mueve al espacio libre de `spaceRanges` y se reescribe el puntero.
- `lea:005a00` es un `lea (d16,PC),aN` del 68000, que es como el código llega a los textos que tiene cerca. Ahí no se guarda la dirección sino la distancia, y son 16 bits con signo, así que el texto sólo se puede mover a menos de 32 KB del propio `lea`. Si hace falta llevarlo más lejos se desvía el `lea` por un trampolín (ver `codeSpace` más abajo).

Al buscar el puntero de un texto se mira primero si hay un `lea` que apunte justo a él, y sólo si no lo hay se recurre al puntero absoluto. Y no vale cualquier sitio donde aparezcan esos tres bytes: un puntero de tres bytes es la parte baja de una palabra larga `00xxxxxx`, así que tiene que empezar en dirección impar, llevar delante un cero y estar a menos de 32 KB de su texto, que es donde el juego pone sus tablas de punteros. Sin estas comprobaciones se cuelan coincidencias de los gráficos: en esta ROM, la mitad larga de los punteros absolutos que salían eran casualidades, y escribir en ellas estropeaba los gráficos y dejaba el texto sin apuntar, o sea en blanco.

Los punteros del fichero de textos se repasan también al inyectar, así que un fichero hecho con una versión anterior sigue valiendo: los que no eran punteros se descartan y se avisa de cuántos había. Volver a extraer sólo sirve para quitarlos del fichero.

#### Cadenas de textos

Hay bloques donde el juego no apunta a cada texto, sino sólo al primero, y va sacando los demás recorriendo la ROM de terminador en terminador. Los créditos son así: son cuarenta líneas seguidas y en toda la ROM sólo hay tres `lea` que apunten a ellas, uno por cada rótulo. No hay ninguna tabla de punteros.

Esos textos no se pueden mover de uno en uno, así que se tratan como una cadena:

- Si cada línea cabe en su sitio, se escriben donde estaban y no se toca nada más.
- Si alguna se pasa pero la cadena entera todavía cabe, se reparte el hueco entre todas: se escriben pegadas y lo que sobra se rellena de ceros. Da igual el reparto entre líneas mientras el total cuadre.
- Si tampoco así cabe, se mueve la cadena entera al espacio libre y se retoca el puntero de la primera línea.

La cadena se lee de la propia ROM, no del fichero de textos, así que da igual que el fichero venga filtrado con `texts`: las líneas que falten se copian tal cual estaban. Una línea en blanco del rótulo (dos terminadores seguidos) también cuenta como un texto más y se respeta.

#### Trampolines

Un `lea (d16,PC)` sólo alcanza 32 KB, y en los créditos no hay tanto espacio libre cerca. Para llevar un texto más lejos se cambia el `lea` por un `bsr.w` de cuatro bytes a un trampolín que carga la dirección entera y vuelve:

```
lea (xxxxxxxx).l,aN
rts
```

Ocupan lo mismo y hacen lo mismo, y ninguna de las dos instrucciones toca los flags. Los ocho bytes del trampolín salen de la propiedad `codeSpace`, que son huecos de la ROM donde se puede escribir código; tienen que caer a menos de 32 KB del `lea`, así que conviene dar varios repartidos por la zona de código:

```properties
codeSpace=0x1c0,0x1ef#0xd09a,0xd0d3
```

En esta ROM `0x1C0` son los bytes reservados de la cabecera de Mega Drive y `0xD09A` es relleno entre dos rutinas. Si no hay hueco a tiro se avisa y el texto se corta, en vez de escribir un salto que no llega.

Con la propiedad `texts` se limita la extracción a una lista de direcciones, que es cómodo cuando ya se han descartado a mano los falsos positivos:

```properties
texts=000100#000113#00013e
```

### Gráficos

Los bloques comprimidos se dibujan como hojas de tiles de Mega Drive: 8x8 píxeles de 4 bits, dos píxeles por byte, 32 bytes por tile, 16 tiles por fila. El PNG sale indexado de 4 bits, así que al abrirlo se sigue trabajando con los 16 índices que guarda la ROM.

Lo que se lee del PNG al inyectar son los índices, no los colores. La paleta es sólo para poder ver el dibujo: da igual acertar con ella, la ida y vuelta sale exacta de todos modos. Si el editor ha convertido el PNG a color, se busca para cada píxel el color más parecido de la paleta.

No hace falta que un bloque sea un número redondo de tiles: la última fila se rellena y al volver se recorta al tamaño que tenía en la ROM. Tampoco todos los bloques comprimidos son gráficos; los que no lo son se ven como ruido, pero van y vuelven igual de bien.

El ancho del PNG marca cuántos tiles hay por fila, así que conviene no cambiarlo. Si se cambia, se avisa por consola.

#### Pantallas completas

Los fondos no son una hoja de tiles suelta: el VDP los dibuja con un mapa de 40x28 casillas, una por cada tile de la pantalla visible, donde cada casilla es una palabra `P CC V H NNNNNNNNNNN` con prioridad, línea de paleta, los dos volteos y el índice de tile. En la ROM hay 18 bloques que son justo un mapa de esos.

Cuando un mapa y su bloque de gráficos se reconocen como pareja, en vez de las dos hojas sueltas sale un único `scene_<mapa>_<graficos>.png` de 320x224 con la pantalla montada, volteos incluidos.

Se pueden editar. Al inyectar, si la imagen dibuja lo mismo que había, los dos bloques se dejan intactos y la ROM sale idéntica byte a byte. En cuanto cambia algo, la imagen se parte en casillas de 8x8, se juntan las repetidas mirando también los cuatro volteos, y se rehacen el bloque de gráficos y el mapa. De cada casilla se conservan la prioridad y la línea de paleta, que no se pueden sacar de los píxeles.

Como al rehacerlo se juntan los tiles repetidos, lo normal es que quepa; si aun así no cabe, se avisa y se deja el bloque como estaba. El tamaño de la imagen no se puede cambiar.

Las parejas se indican en la configuración, con direcciones en hexadecimal:

```properties
scenes=2f2050,255000#1c8e90,1a15c0
```

Lo que no esté ahí se intenta emparejar solo: un mapa va con el bloque que tiene exactamente los tiles que usa, siempre que no haya otro candidato con esa misma cuenta. Acierta a menudo, pero no siempre —hay bloques que cuadran en número y no son la pareja—, así que un `-` a la derecha descarta un mapa que se empareje mal:

```properties
scenes=1ca6e0,-
```

No todos los mapas están comprimidos: alguno va suelto en la ROM, y entonces se lee y se escribe tal cual, sin pasar por el compresor. Se reconoce solo, por no ser ninguno de los bloques RNC.

Un mismo bloque de gráficos puede dar servicio a varias pantallas. En ese caso se rehacen todas juntas al inyectar, y los tiles que ya había no se renumeran, para no estropear las demás; sólo se añaden al final los que hagan falta.

Ojo: si al inyectar un bloque se reubica por no caber, cambia de dirección, y entonces las direcciones de `scenes` se quedan viejas para esa ROM parcheada. Para volver a extraer de ella hay que actualizarlas.

#### Bloques sin comprimir

Hay trozos de la ROM con tiles que no están comprimidos. Se listan con la propiedad `bins`, con la primera y la última dirección de cada uno, y salen como `bin_019c00.png` igual que los demás gráficos:

```properties
bins=105472,107040
```

Van y vuelven byte a byte, así que un bloque que no sean gráficos se ve como ruido pero no se estropea. La imagen puede tener más tiles que el bloque, porque la última fila se rellena; al inyectar se recorta al tamaño del hueco, que se saca de la propia propiedad.

Estos bloques nunca se reubican ni cambian de tamaño: no tienen por qué ser direccionables por puntero, así que la imagen tiene que caber en su hueco.

#### Paletas

Una línea de paleta de Mega Drive son 16 palabras big-endian con el formato `0000 BBB0 GGG0 RRR0`. El color 0 es transparente —en pantalla se ve el color de fondo del VDP, no lo que guarde la paleta—, así que se dibuja negro, que es lo que se ve.

Muchas se encuentran solas: el juego guarda tablas donde el puntero a la paleta va justo delante del puntero al bloque que la usa, así que se buscan esos pares comprobando que al otro lado hay de verdad formato CRAM. En esta ROM salen 31 bloques con su paleta.

Las de las pantallas completas no aparecen así, y se han sacado siguiendo el código: la rutina de `0x12C7A` es la que vuelca paletas a CRAM (recibe hasta cuatro punteros en `a0`-`a3` y una máscara en `d0` que dice qué líneas cargar). Buscando quién la llama y qué tenía en `a0` salen las de la pantalla de título, «Test your might», los créditos, Goro y el marco de los dragones, todas comprobadas mirando el resultado.

Lo que no aparezca así se puede indicar a mano, con direcciones en hexadecimal:

```properties
palettes=1959bc,19597a
```

Y si no hay ninguna, se dibuja con una rampa de grises, que deja los 16 índices bien distinguibles.

Los samples PCM se extraen a WAV. No hay que configurarlos: se busca en la ROM la tabla que los describe y se vuelca cada entrada a `sample_<id>_<direccion>.wav`, con la frecuencia a la que el juego reproduce ese sample.

La tabla son entradas de ocho bytes: identificador (1), dirección del PCM (3), longitud (2) y velocidad de reproducción (2). El PCM es de 8 bits con signo; WAV lo guarda sin signo, así que la conversión es un XOR con `0x80` en los dos sentidos.

### Inyección:

`MortalSDK i "mortal kombat.bin" [configuracion.properties]`

Se generará un fichero nuevo con los recursos inyectados. Si se especifica `configuracion.properties` se usará esta.

Los samples se inyectan después de los textos: los dos se reparten el espacio libre de `spaceRanges`, y una traducción que no cabe se pierde, mientras que un sample que no cabe se queda como estaba.

Con los samples PCM se sigue este criterio:

- Si se ha borrado su WAV de la carpeta `extracted`, el sample original se queda como está.
- Si el WAV no se ha modificado respecto a la ROM original, tampoco se toca nada.
- Si cabe en su hueco, se escribe ahí y se acorta la longitud de su entrada. No se rellena el sobrante, porque los samples van pegados unos a otros y se pisaría el siguiente.
- Si no cabe, se mueve al espacio libre de `spaceRanges` y se corrigen la dirección y la longitud de su entrada. Si no hay sitio, no se inyecta y se avisa por consola.

Se admite cualquier WAV PCM de 8 o 16 bits, mono o estéreo, y a cualquier frecuencia: no se remuestrea, se escribe en la tabla la velocidad de reproducción más parecida a la del WAV. El máximo que alcanza el reproductor de la ROM son unos 13,8 kHz.

Con los bloques de `bins` se sigue el mismo criterio: el que ya no tenga PNG en `extracted` y el que no haya cambiado se quedan como estaban. No se reubican ni cambian de tamaño, porque no tienen por qué ser direccionables por puntero.

### Rutinas anuladas

La propiedad `skipRoutines` es una lista de direcciones a las que se les pone un `rts` al inyectar, con lo que la rutina deja de hacer nada y quien la llame sigue como si tal cosa:

```properties
skipRoutines=0x19a70
```

En esta ROM sirve para quitar el logo de Sega. Lo dibuja la rutina de `0x19A70`, que sube a la VDP los 1568 bytes de tiles de `0x19C00` —el bloque `bin_019c00`— y hace su fundido; sólo se la llama desde el arranque, en `0x1273E`, y se reserva y devuelve ella misma la pila que usa, así que saltársela entera no deja nada a medias. Justo después, el arranque vuelve a programar la VDP entera por su cuenta.

Con eso, y como el vector RESET apunta a la intro, lo primero que se ve al encender y al resetear es la intro, y de ahí se pasa directamente al juego.

### Parche IPS

Al terminar la inyección se escribe también un `.ips` junto a la ROM, que es lo que se reparte: la ROM entera es casi toda del juego original, y el parche sólo lleva los bytes que ha puesto este programa. Se genera solo, sin configurar nada.

```
Parche escrito en: Mortal Kombat Arcade Edition v2-0.bin.ips (216.296 bytes, 199.712 cambiados de 4.194.304)
```

Las tiradas largas de un mismo byte van en RLE, los cambios separados por menos de cinco bytes iguales se juntan en un registro, y las tiradas de más de 64 KB se reparten entre varios, que es lo que cabe en el tamaño de un registro. Si la inyección no ha cambiado nada se avisa: un parche sin registros no sirve de nada y además hay programas que lo dan por incompleto.

### Intro

Se puede poner una intro delante del juego: al encender se ve la intro, y con START (o A), o pasado un rato, entra al juego. Se activa con dos propiedades:

```properties
intro=charnego_introFinal.md
introSpace=0x396000,0x3AFEFF#0x3D0A00,0x3EFEFF
```

`intro` es la ROM de Mega Drive de la intro, e `introSpace` las zonas de la ROM que puede usar para repartir sus trozos. Si falta cualquiera de las dos, este paso no hace nada.

El juego se queda donde está, porque su código está lleno de direcciones absolutas. La intro se parte en trozos —código, 16 fotogramas comprimidos con RLE, la muestra PCM y el driver Z80—, se reparten por los huecos que se le indiquen, y sus direcciones absolutas se recolocan a donde haya caído cada uno. Después se cambia el vector RESET para que apunte a un arranque nuestro, que desbloquea el TMSS y salta a la intro; al salir se callan el PSG y el YM2612, se resetea el Z80 y se devuelve el control a la entrada original del juego, que se lee de los vectores y no se supone.

Sobre el espacio hay dos cosas que tener en cuenta:

- Los huecos de `introSpace` los indica quien prepara la configuración, y el programa no puede saber si el juego lee de verdad esos bytes. Que estén a cero no lo demuestra.
- Lo que ya use `spaceRanges` se descuenta solo, porque de ahí tiran los textos y los bloques. Y antes de escribir nada se comprueba trozo a trozo contra la ROM original: si un byte de la zona ya lo había cambiado un paso anterior, se aborta en vez de comerse una reubicación.

La ROM no crece: si los trozos no caben en los huecos indicados, se avisa.

Esto viene del insertador de intros de CholeilSDK, que a su vez viene de `insertar_intro.py` de ScorpioN-MsX. Va atado a una intro concreta y se niega a funcionar con otra, porque recolocar a ciegas una que no conoce daría una ROM rota.

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

- Los samples se inyectan después de los textos, para que una traducción que no quepa no se quede sin sitio.
- Los avisos de los samples dicen el nombre del fichero, no el número del sample.
- La propiedad `skipRoutines` anula rutinas con un `rts`; con ella se quita el logo de Sega y la intro pasa a ser lo primero que se ve.
- Los bloques sin comprimir de `bins` también se extraen y se inyectan como PNG, no en crudo.
- Al inyectar se escribe también un parche `.ips` con lo que ha cambiado, que es lo que se reparte.
- Los créditos y los demás bloques de textos encadenados se reubican enteros, no línea a línea.
- Los punteros del fichero de textos se repasan al inyectar, así que los ficheros ya empezados siguen valiendo sin volver a extraer.
- Un `lea` puede desviarse por un trampolín, con lo que un texto suyo ya puede irse a cualquier parte de la ROM. Los huecos para el trampolín se indican con la propiedad `codeSpace`.
- Los punteros absolutos se comprueban antes de darlos por buenos: hasta ahora se colaban coincidencias de los gráficos, y escribir en ellas estropeaba la ROM y dejaba el texto sin apuntar.
- Un texto que no cabe y no se puede mover ya no se escribe más allá de su hueco, pisando al siguiente.

- Los gráficos se extraen y se inyectan como PNG en vez de como volcados de tiles. Ya no se generan los `.bin`.
- Los fondos que tienen mapa de tiles salen montados como pantalla de 320x224, y se pueden editar así.
- Las parejas de mapa y gráficos se pueden indicar con la propiedad `scenes`, y el mapa puede ir sin comprimir.
- En el fichero de textos las direcciones van en hexadecimal y el puntero dice si es absoluto o un `lea`.
- Se reconocen los punteros `lea (d16,PC)`, y con ellos se reubican textos si el hueco queda a tiro.
- La propiedad `texts` limita la extracción a las direcciones que se le indiquen.
- Se puede poner una intro delante del juego con `intro` e `introSpace`.
- Los rangos de la configuración admiten hexadecimal con el prefijo `0x`.
- Las paletas de los gráficos se buscan solas en la ROM; también se pueden indicar a mano.
- La configuración trae las paletas de nueve de las once pantallas, sacadas del código que las carga.
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