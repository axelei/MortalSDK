# MortalSDK
Extractor e insertor de bloques comprimidos y textos de Mortal Kombat (Mega Drive). Está escrito en Java y preparado para compilarlo AoT con GraalVM.

¿Por qué en Java? Porque es el lenguaje que, en este momento, me da de comer. :)

## Uso:

### Extracción:

`MortalSDK x "mortal kombat.bin"`

En la carpeta `extracted` se generarán los bloques descomprimidos. Los gráficos están en formato tiles de Mega Drive, es decir, 4bpp linear. El texto estará en el nombre de la ROM más `.txt`.

### Inyección:

`MortalSDK.exe i "mortal kombat.bin"`

Se generará un fichero nuevo con los recursos inyectados.

## Requisitos

Require `rnc_propack_x64.exe` en el mismo directorio. Se puede obtener de: https://github.com/lab313ru/rnc_propack_source/releases

Con pequeños ajustes se puede usar con otras ROMs y otros sistemas operativos. Añade un 'issue' si tienes alguna propuesta de cambio.

## Autoría y reconocimientos

Gracias a [Rael G. C.](https://github.com/raelgc) por la información que me faltaba. 

By Krusher, licenciado bajo GPL 3. Por favor, consulta el fichero LICENSE.