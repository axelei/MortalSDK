package net.krusher.mortalsdk;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;

public class TexticleService {

    private static final String DEFAULT_TEXT_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!?.,0123456789:'\" ";
    private static final int MAX_TBL_KEY_LENGTH = 3;

    /** El byte que cierra una cadena. Lo que va detrás es ya la siguiente. */
    private static final byte TERMINATOR = 0x00;

    /**
     * Hasta dónde nos fiamos de un puntero absoluto encontrado a base de buscar el valor por la ROM.
     * <p>
     * Un valor de tres bytes sale por casualidad cada dos por tres en los gráficos, así que sólo se da por
     * bueno el que cae cerca de su texto, que es donde el juego pone de verdad sus tablas de punteros. Son
     * los mismos 32 KB que alcanza un lea, por no inventar otro número.
     */
    private static final int POINTER_REACH = 0x8000;

    /** Tamaño del trampolín: {@code lea (xxxxxxxx).l,aN} más {@code rts}. */
    private static final int TRAMPOLINE_SIZE = 8;

    private static final int LEA_PC_OPCODE = 0x41FA;
    private static final int LEA_LONG_OPCODE = 0x41F9;
    private static final int REGISTER_MASK = 0x0E00;
    private static final int BSR_WORD_OPCODE = 0x6100;
    private static final int RTS_OPCODE = 0x4E75;

    public static List<Texticle> findTexticles(byte[] fileData) {
        List<Texticle> texts = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        boolean inText = false;
        int length = 0;
        // se llega uno más allá del final para cerrar el texto que quedara a medias
        for (int i = 0; i <= fileData.length; i++) {
            // saltarse un byte corta el texto que se llevara: si no, se pegaría con el de después y la
            // dirección que se apunta sería la de ninguno de los dos
            int extractedLength = i < fileData.length && inRange(i) && !isInsideFixed(i)
                    ? isChar(i, fileData) : 0;
            if (extractedLength > 0) {
                inText = true;
                if (Objects.isNull(App.tbl)) {
                    buffer.append((char) fileData[i]);
                } else {
                    byte[] datum = new byte[extractedLength];
                    System.arraycopy(fileData, i, datum, 0, extractedLength);
                    String hexString = TblService.byteArrayToHexString(datum);
                    if (App.tbl.containsKey(hexString)) {
                        buffer.append(App.tbl.get(hexString));
                    } else {
                        buffer.append((char) fileData[i]);
                    }
                }
                i += extractedLength - 1;
                length += extractedLength;
            } else if (inText) {
                if (length > App.config.minChars() && wanted(i - length)) {
                    texts.add(new Texticle(i - length, length, buffer.toString(),
                            findPointer(i - length, fileData)));
                }
                length = 0;
                buffer = new StringBuilder();
                inText = false;
            }
        }
        texts.addAll(fixedTexts(fileData));
        texts.sort(Comparator.comparingInt(Texticle::address));
        return texts;
    }

    /**
     * Los campos de tamaño fijo, que salen enteros y con su tamaño exacto en vez de por lo que ocupe el texto
     * que haya dentro. Son los nombres de la cabecera de Mega Drive y cosas así: el juego los lee siempre del
     * mismo sitio y del mismo largo, así que ni se mueven ni se dejan partir en dos.
     */
    private static List<Texticle> fixedTexts(byte[] fileData) {
        List<Texticle> texts = new ArrayList<>();
        for (Range range : App.config.fixedTexts()) {
            if (!wanted(range.getFrom()) || range.getTo() >= fileData.length) {
                continue;
            }
            byte[] bytes = Arrays.copyOfRange(fileData, range.getFrom(), range.getTo() + 1);
            texts.add(new Texticle(range.getFrom(), range.size(),
                    new String(bytes, StandardCharsets.ISO_8859_1), null));
        }
        return texts;
    }

    /** Si esa dirección cae dentro de un campo de tamaño fijo. */
    private static boolean isInsideFixed(int address) {
        for (Range range : App.config.fixedTexts()) {
            if (range.isInRange(address)) {
                return true;
            }
        }
        return false;
    }

    /** Si ahí empieza un campo de tamaño fijo. */
    static boolean isFixed(int address) {
        for (Range range : App.config.fixedTexts()) {
            if (range.getFrom() == address) {
                return true;
            }
        }
        return false;
    }

    /**
     * Busca desde dónde se apunta a un texto.
     * <p>
     * Primero se buscan los {@code lea (d16,PC)} del 68000, que es como el código llega a los textos que
     * tiene cerca, y sólo si no hay ninguno se recurre al puntero absoluto de tres bytes. El orden importa:
     * un valor de tres bytes puede aparecer por casualidad en cualquier sitio, mientras que un lea que
     * apunta justo al texto no es casualidad.
     */
    public static Texticle.Pointer findPointer(Integer value, byte[] fileData) {
        if (Objects.isNull(value)) {
            return null;
        }
        Integer lea = findLeaAddress(value, fileData);
        if (Objects.nonNull(lea)) {
            return new Texticle.Pointer(lea, true);
        }
        Integer absolute = findTextPointerAddress(value, fileData);
        return Objects.isNull(absolute) ? null : new Texticle.Pointer(absolute, false);
    }

    /**
     * Busca un {@code lea (d16,PC),aN} que apunte a {@code value}. Son cuatro bytes: la instrucción y una
     * distancia con signo de 16 bits contada desde la propia distancia.
     */
    public static Integer findLeaAddress(int value, byte[] fileData) {
        for (int at = 0; at + 4 <= fileData.length; at += 2) {
            int opcode = readWord(fileData, at);
            if (!isLeaPcRelative(opcode)) {
                continue;
            }
            int displacement = (short) readWord(fileData, at + 2);
            if (at + 2 + displacement == value) {
                return at;
            }
        }
        return null;
    }

    /** lea (d16,PC),aN: 0x41FA para a0, 0x43FA para a1, y así hasta a6. */
    private static boolean isLeaPcRelative(int opcode) {
        return (opcode & 0xF1FF) == LEA_PC_OPCODE && ((opcode >> 9) & 7) <= 6;
    }

    /**
     * Busca un puntero absoluto de tres bytes que valga {@code value}.
     * <p>
     * No vale cualquier sitio donde aparezcan esos tres bytes: un puntero de tres bytes es la parte baja de
     * una palabra larga {@code 00xxxxxx}, así que tiene que empezar en dirección impar y llevar delante un
     * cero. Sin esta comprobación se cuelan coincidencias de los gráficos, y escribir en ellas estropea la
     * ROM y deja el texto sin apuntar.
     */
    public static Integer findPointerAddress(Integer value, byte[] fileData) {
        return findPointerAddress(value, fileData, Integer.MAX_VALUE);
    }

    /** Como {@link #findPointerAddress}, pero exigiendo además que el puntero esté cerca de su texto. */
    public static Integer findTextPointerAddress(Integer value, byte[] fileData) {
        return findPointerAddress(value, fileData, POINTER_REACH);
    }

    private static Integer findPointerAddress(Integer value, byte[] fileData, int reach) {
        if (Objects.isNull(value)) {
            return null;
        }
        byte high = (byte) ((value >> 16) & 0xFF);
        byte middle = (byte) ((value >> 8) & 0xFF);
        byte low = (byte) (value & 0xFF);
        // en dirección impar, que es donde cae la parte baja de una palabra larga alineada
        for (int i = 1; i < fileData.length - 2; i += 2) {
            if (fileData[i] != high || fileData[i + 1] != middle || fileData[i + 2] != low) {
                continue;
            }
            if (fileData[i - 1] != 0) {
                continue;
            }
            if (Math.abs(i - value) > reach) {
                continue;
            }
            return i;
        }
        return null;
    }

    /**
     * Escribe en el puntero la nueva dirección del texto. Un lea guarda la distancia, no la dirección, y esa
     * distancia es de 16 bits con signo: si el destino queda a más de 32 KB se intenta desviar el lea por un
     * trampolín. Devuelve false si tampoco eso se puede.
     */
    public static boolean writePointer(Texticle.Pointer pointer, int target, byte[] fileData) {
        if (!pointer.lea()) {
            writeThreeBytes(fileData, pointer.address(), target);
            return true;
        }
        int displacement = target - (pointer.address() + 2);
        if (displacement < Short.MIN_VALUE || displacement > Short.MAX_VALUE) {
            return writeTrampoline(pointer, target, fileData);
        }
        writeWord(fileData, pointer.address() + 2, displacement);
        return true;
    }

    /**
     * Cambia un {@code lea (d16,PC),aN} por un {@code bsr.w} a un trampolín que carga la dirección entera y
     * vuelve. Ocupan lo mismo, cuatro bytes, y el efecto es el mismo: aN acaba apuntando al texto y no se
     * toca ningún flag. Así un texto puede irse al final de la ROM aunque lo apunte un lea.
     * <p>
     * El trampolín sale de {@code codeSpace} y tiene que quedar a menos de 32 KB del propio lea, que es lo
     * que alcanza el bsr.
     */
    private static boolean writeTrampoline(Texticle.Pointer pointer, int target, byte[] fileData) {
        int opcode = readWord(fileData, pointer.address());
        if (!isLeaPcRelative(opcode)) {
            Log.pnl("En {0} no hay un lea, no se puede desviar.", Integer.toHexString(pointer.address()));
            return false;
        }
        Integer trampoline = getCodeAddress(TRAMPOLINE_SIZE, pointer.address());
        if (Objects.isNull(trampoline)) {
            return false;
        }
        writeWord(fileData, trampoline, LEA_LONG_OPCODE | (opcode & REGISTER_MASK));
        writeThreeBytes(fileData, trampoline + 3, target);
        fileData[trampoline + 2] = 0;
        writeWord(fileData, trampoline + 6, RTS_OPCODE);

        writeWord(fileData, pointer.address(), BSR_WORD_OPCODE);
        writeWord(fileData, pointer.address() + 2, trampoline - (pointer.address() + 2));
        Log.pnl("El lea de {0} se desvía por un trampolín en {1}.",
                Integer.toHexString(pointer.address()), Integer.toHexString(trampoline));
        return true;
    }

    /**
     * Reserva un hueco de {@code codeSpace} para código, en dirección par y a tiro de un bsr desde
     * {@code near}.
     */
    public static Integer getCodeAddress(int size, int near) {
        for (Range range : App.config.codeSpace()) {
            int from = (range.getFrom() + 1) & ~1;
            if (from + size - 1 > range.getTo()) {
                continue;
            }
            int displacement = from - (near + 2);
            if (displacement < Short.MIN_VALUE || displacement > Short.MAX_VALUE) {
                continue;
            }
            range.setFrom(from + size);
            return from;
        }
        Log.pnl("No queda hueco de código a menos de 32 KB de {0}.", Integer.toHexString(near));
        return null;
    }

    /** Si la configuración trae una lista de textos, sólo se extraen ésos. */
    private static boolean wanted(int address) {
        return App.config.texts().isEmpty() || App.config.texts().contains(address);
    }

    public static boolean inRange(int i) {
        if (App.config.textRanges().isEmpty()) {
            return true;
        }
        for (Range range : App.config.textRanges()) {
            if (range.isInRange(i)) {
                return true;
            }
        }
        return false;
    }

    public static void dumpTexticles(List<Texticle> texticles, String file) throws IOException {
        File outputFile = new File(file + ".txt");
        FileWriter fileWriter = new FileWriter(outputFile);
        PrintWriter printWriter = new PrintWriter(fileWriter);

        for (Texticle texticle : texticles) {
            printWriter.println(texticle.format());
        }

        printWriter.close();
    }

    private static int isChar(int position, byte[] array) {
        if (Objects.isNull(App.tbl)) {
            byte fileDatum = array[position];
            for (byte theChar : DEFAULT_TEXT_CHARS.getBytes(StandardCharsets.ISO_8859_1)) {
                if (fileDatum == theChar) {
                    return 1;
                }
            }
        } else {
            for (int i = MAX_TBL_KEY_LENGTH; i > 0; i--) {
                if (position + i >= array.length) {
                    return 0;
                }
                byte[] datum = new byte[i];
                System.arraycopy(array, position, datum, 0, i);

                if (App.tbl.containsKey(TblService.byteArrayToHexString(datum))) {
                    return datum.length;
                }
            }
        }
        return 0;
    }

    public static List<Texticle> readTexticles(String file) throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(file + ".txt"));
        List<Texticle> texticles = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("#");
            int address = Texticle.parseAddress(parts[0]);
            int size = Integer.parseInt(parts[1].trim());
            String text = parts[2];
            Texticle.Pointer pointer = parts.length > 3 ? Texticle.Pointer.parse(parts[3]) : null;
            texticles.add(new Texticle(address, size, text, pointer));
        }
        return texticles;
    }

    public static void insertTexticles(String file, byte[] fileData, byte[] originalData) throws IOException {
        List<Texticle> texticles = checkPointers(readTexticles(file), originalData);
        texticles.sort(Comparator.comparingInt(Texticle::address));
        Set<Integer> pointed = pointedAddresses(originalData);
        Set<Integer> written = new HashSet<>();
        // los campos fijos se apuntan ya para que no se los lleve ninguna cadena, pero se escriben al final:
        // así, si el fichero trae de antes un texto viejo que los pisaba, manda el campo
        for (Texticle texticle : texticles) {
            if (isFixed(texticle.address())) {
                written.add(texticle.address());
            }
        }
        for (Texticle texticle : texticles) {
            if (written.contains(texticle.address())) {
                // ya se ha escrito detrás de otro, como parte de su cadena
                continue;
            }
            List<Texticle> chain = chainOf(texticle, texticles, originalData, pointed);
            chain.forEach(member -> written.add(member.address()));
            writeChain(chain, fileData, originalData);
        }
        for (Texticle texticle : texticles) {
            if (isFixed(texticle.address())) {
                writeFixed(texticle, fileData);
            }
        }
    }

    /**
     * Repasa los punteros del fichero de textos contra la ROM y descarta los que no lo son.
     * <p>
     * Los ficheros hechos con versiones anteriores traen punteros absolutos que salieron de una coincidencia
     * cualquiera dentro de los gráficos. Escribir en ellos estropea la ROM y deja el texto sin apuntar, o
     * sea en blanco, así que se repasan aquí y no hace falta volver a extraer para arreglarlo.
     */
    static List<Texticle> checkPointers(List<Texticle> texticles, byte[] originalData) {
        List<Texticle> checked = new ArrayList<>();
        int discarded = 0;
        for (Texticle texticle : texticles) {
            if (Objects.isNull(texticle.pointer()) || isRealPointer(texticle, originalData)) {
                checked.add(texticle);
                continue;
            }
            discarded++;
            checked.add(new Texticle(texticle.address(), texticle.size(), texticle.text(), null));
        }
        if (discarded > 0) {
            Log.pnl("{0} punteros del fichero de textos no lo eran y se descartan. Conviene volver a "
                    + "extraer para quitarlos del fichero.", discarded);
        }
        return checked;
    }

    /** Si en esa dirección hay de verdad un puntero al texto, y no tres bytes que valen lo mismo. */
    private static boolean isRealPointer(Texticle texticle, byte[] originalData) {
        Texticle.Pointer pointer = texticle.pointer();
        int at = pointer.address();
        if (at < 1 || at + 4 > originalData.length) {
            return false;
        }
        if (pointer.lea()) {
            return isLeaPcRelative(readWord(originalData, at))
                    && at + 2 + (short) readWord(originalData, at + 2) == texticle.address();
        }
        int value = ((originalData[at] & 0xFF) << 16) | ((originalData[at + 1] & 0xFF) << 8)
                | (originalData[at + 2] & 0xFF);
        return value == texticle.address() && at % 2 == 1 && originalData[at - 1] == 0
                && Math.abs(at - texticle.address()) <= POINTER_REACH;
    }

    /**
     * Escribe un campo de tamaño fijo: el texto tal cual en ASCII, cortado si se pasa y rellenado con
     * espacios si se queda corto, que es como los rellena la cabecera de Mega Drive. Ni se mueve ni se le
     * busca puntero: el juego lo lee siempre de la misma dirección.
     */
    private static void writeFixed(Texticle texticle, byte[] fileData) {
        byte[] bytes = texticle.toRawAsciiBytes();
        if (bytes.length > texticle.size()) {
            Log.pnl("Alerta: \"{0}\" tiene {1} caracteres y en {2} sólo caben {3}. Se cortará.",
                    texticle.text(), bytes.length, Integer.toHexString(texticle.address()), texticle.size());
        }
        byte[] field = new byte[texticle.size()];
        Arrays.fill(field, Texticle.ASCII_SPACE);
        System.arraycopy(bytes, 0, field, 0, Math.min(bytes.length, field.length));
        System.arraycopy(field, 0, fileData, texticle.address(), field.length);
    }

    /**
     * La cadena de textos que cuelga de éste.
     * <p>
     * Hay sitios donde el juego no apunta a cada texto, sino sólo al primero, y va sacando los demás
     * recorriendo la ROM de terminador en terminador; los créditos son así. Esos textos no se pueden mover
     * de uno en uno: hay que llevárselos todos juntos y en el mismo orden, y sólo se retoca el puntero del
     * primero.
     * <p>
     * La cadena se lee de la ROM original, no del fichero de textos, porque el fichero puede venir filtrado
     * y faltarle alguno. Los que falten se copian tal cual estaban.
     */
    static List<Texticle> chainOf(Texticle head, List<Texticle> texticles, byte[] originalData,
                                  Set<Integer> pointed) {
        List<Texticle> chain = new ArrayList<>();
        chain.add(head);
        int at = head.address() + head.size();
        while (at < originalData.length && originalData[at] == TERMINATOR) {
            int next = at + 1;
            int length = lengthAt(next, originalData);
            if (length <= 0) {
                // dos terminadores seguidos son una línea en blanco del rótulo, y cuenta como un texto
                // más de la cadena; tres o más son ya el final del bloque
                if (next >= originalData.length || originalData[next] != TERMINATOR
                        || lengthAt(next + 1, originalData) <= 0) {
                    break;
                }
                chain.add(new Texticle(next, 0, "", null));
                at = next;
                continue;
            }
            if (pointed.contains(next)) {
                // tiene puntero propio: el juego llega a él por su cuenta y no hay que arrastrarlo
                break;
            }
            chain.add(texticleAt(next, length, texticles, originalData));
            at = next + length;
        }
        return chain.size() > 1 ? chain : List.of(head);
    }

    /**
     * Todas las direcciones de la ROM a las que apunta algo, sea un lea o un puntero absoluto de los que nos
     * fiamos. Se saca de una pasada porque hace falta para cada texto de la cadena y buscarlo uno a uno
     * saldría carísimo.
     */
    static Set<Integer> pointedAddresses(byte[] data) {
        Set<Integer> pointed = new HashSet<>();
        for (int at = 0; at + 4 <= data.length; at += 2) {
            if (isLeaPcRelative(readWord(data, at))) {
                pointed.add(at + 2 + (short) readWord(data, at + 2));
            }
        }
        for (int at = 1; at + 3 <= data.length; at += 2) {
            if (data[at - 1] != 0) {
                continue;
            }
            int value = ((data[at] & 0xFF) << 16) | ((data[at + 1] & 0xFF) << 8) | (data[at + 2] & 0xFF);
            if (Math.abs(at - value) <= POINTER_REACH) {
                pointed.add(value);
            }
        }
        // El operando de un "move.l #dirección, lo que sea" es un puntero se ponga donde se ponga: no vale
        // pedirle que esté cerca del texto, que el juego puede encolar un rótulo desde la otra punta de la
        // ROM. Y no se cuela cualquier cosa, porque tiene que ser justo el inmediato de esa instrucción.
        for (int at = 0; at + 6 <= data.length; at += 2) {
            if ((readWord(data, at) & 0xF03F) != MOVE_L_IMMEDIATE || data[at + 2] != 0) {
                continue;
            }
            pointed.add(((data[at + 3] & 0xFF) << 16) | ((data[at + 4] & 0xFF) << 8) | (data[at + 5] & 0xFF));
        }
        return pointed;
    }

    /** Un {@code move.l #inmediato, destino}: 0010 rrr mmm 111 100, o sea opcode &amp; 0xF03F == 0x203C. */
    static final int MOVE_L_IMMEDIATE = 0x203C;

    /** Cuántos bytes de texto seguidos hay a partir de esta dirección. */
    private static int lengthAt(int address, byte[] data) {
        int length = 0;
        int at = address;
        while (at < data.length) {
            int size = isChar(at, data);
            if (size <= 0) {
                break;
            }
            at += size;
            length += size;
        }
        return length;
    }

    /** El texto traducido si está en el fichero, y si no el original de la ROM. */
    private static Texticle texticleAt(int address, int length, List<Texticle> texticles, byte[] originalData) {
        for (Texticle texticle : texticles) {
            if (texticle.address() == address) {
                return texticle;
            }
        }
        byte[] original = Arrays.copyOfRange(originalData, address, address + length);
        return new Texticle(address, length, new String(original, StandardCharsets.ISO_8859_1), null);
    }

    /**
     * Escribe una cadena de textos. Si cada uno cabe en su sitio se escriben donde estaban; si alguno se ha
     * pasado de largo se reaprovecha el hueco de todos juntos, y si aun así no caben se mueve la cadena
     * entera y se retoca el puntero del primero.
     */
    private static void writeChain(List<Texticle> chain, byte[] fileData, byte[] originalData) {
        Texticle head = chain.getFirst();
        List<byte[]> encoded = chain.stream().map(TexticleService::encode).toList();
        boolean allFit = true;
        for (int i = 0; i < chain.size(); i++) {
            allFit &= encoded.get(i).length <= chain.get(i).size();
        }
        if (allFit) {
            for (int i = 0; i < chain.size(); i++) {
                writeTexticle(chain.get(i).address(), encoded.get(i), fileData, chain.get(i).size(), null);
            }
            return;
        }
        if (chain.size() == 1) {
            writeTexticle(head.address(), encoded.getFirst(), fileData, head.size(), head.pointer());
            return;
        }

        Texticle last = chain.getLast();
        int room = last.address() + last.size() - head.address();
        int needed = encoded.stream().mapToInt(bytes -> bytes.length + 1).sum() - 1;
        if (needed <= room) {
            Log.pnl("La cadena de {0} no cabe texto a texto, se reparte el hueco entre todos.",
                    Integer.toHexString(head.address()));
            packChain(encoded, fileData, head.address(), room);
            return;
        }

        Log.p("La cadena de {0} necesita {1} bytes y sólo tiene {2}. ",
                Integer.toHexString(head.address()), needed, room);
        Integer newAddress = Objects.isNull(head.pointer()) ? null : getNewAddress(needed + 1);
        if (Objects.isNull(newAddress) || !writePointer(head.pointer(), newAddress, fileData)) {
            Log.pnl("No se puede mover, se cortan los textos que se pasen.");
            for (int i = 0; i < chain.size(); i++) {
                writeTexticle(chain.get(i).address(), encoded.get(i), fileData, chain.get(i).size(), null);
            }
            return;
        }
        Log.pnl("Se mueve entera a {0}.", Integer.toHexString(newAddress));
        packChain(encoded, fileData, newAddress, needed + 1);
        Arrays.fill(fileData, head.address(), head.address() + room, TERMINATOR);
        freeSpace(head.address(), room);
    }

    /** Escribe los textos uno detrás de otro separados por el terminador, y rellena lo que sobre. */
    private static void packChain(List<byte[]> encoded, byte[] fileData, int address, int room) {
        int at = address;
        for (byte[] bytes : encoded) {
            System.arraycopy(bytes, 0, fileData, at, bytes.length);
            at += bytes.length;
            fileData[at] = TERMINATOR;
            at++;
        }
        if (at < address + room) {
            Arrays.fill(fileData, at, address + room, TERMINATOR);
        }
    }

    /** Pasa el texto a los bytes que van a la ROM, con la tabla de caracteres si la hay. */
    private static byte[] encode(Texticle texticle) {
        if (Objects.isNull(App.tbl)) {
            return texticle.toRawAsciiBytes();
        }
        List<Byte> textDataList = new ArrayList<>();
        for (int i = 0; i < texticle.text().length(); i++) {
            for (int a = MAX_TBL_KEY_LENGTH; a > 0; a--) {
                StringBuilder chars = new StringBuilder();
                for (int b = 0; b < a; b++) {
                    if (i + b >= texticle.text().length()) {
                        chars.append((char) 0x00);
                    } else {
                        chars.append(texticle.text().charAt(i + b));
                    }
                }
                if (App.tbl.containsValue(chars.toString())) {
                    String hexValue = App.tbl.inverse().get(chars.toString());
                    for (byte b : TblService.hexStringToByteArray(hexValue)) {
                        textDataList.add(b);
                    }
                    i += a - 1;
                    break;
                }
            }
        }
        byte[] textData = new byte[textDataList.size()];
        for (int a = 0; a < textDataList.size(); a++) {
            textData[a] = textDataList.get(a);
        }
        return textData;
    }

    private static void writeTexticle(int address, byte[] textData, byte[] fileData, int room,
                                      Texticle.Pointer pointer) {
        if (textData.length == room) {
            System.arraycopy(textData, 0, fileData, address, textData.length);
            return;
        }
        if (textData.length < room) {
            System.arraycopy(textData, 0, fileData, address, textData.length);
            byte[] padding = new byte[room - textData.length];
            System.arraycopy(padding, 0, fileData, address + textData.length, padding.length);
            return;
        }

        String oldText = new String(textData, StandardCharsets.ISO_8859_1);
        Log.p("Alerta: El texto leído \"{0}\" tiene {1} caracteres, pero el texto original tenía {2} caracteres. ", oldText, textData.length, room);
        if (Objects.isNull(pointer)) {
            writeCutText(textData, fileData, address, room);
            return;
        }
        // se pide un byte de más para el terminador: el hueco puede ser uno reciclado, y entonces lo que
        // haya detrás no tiene por qué ser un cero
        Integer newAddress = getNewAddress(textData.length + 1);
        if (Objects.isNull(newAddress)) {
            writeCutText(textData, fileData, address, room);
            return;
        }
        if (!writePointer(pointer, newAddress, fileData)) {
            // un lea sólo alcanza 32 KB y no ha habido sitio para un trampolín cerca
            Log.pnl("El puntero de {0} es un lea y {1} le queda demasiado lejos.",
                    Integer.toHexString(pointer.address()), Integer.toHexString(newAddress));
            writeCutText(textData, fileData, address, room);
            return;
        }

        byte[] padding = new byte[room];
        Arrays.fill(padding, Texticle.ASCII_SPACE);
        System.arraycopy(padding, 0, fileData, address, room);

        Log.pnl("Moviendo el texto a la dirección {0}", Integer.toHexString(newAddress));
        System.arraycopy(textData, 0, fileData, newAddress, textData.length);
        fileData[newAddress + textData.length] = TERMINATOR;
        freeSpace(address, room);
    }

    private static void writeCutText(byte[] textData, byte[] fileData, int address, int room) {
        Log.pnl("Se cortará el texto.");
        System.arraycopy(textData, 0, fileData, address, room);
    }

    /** La zona que tapa la SRAM, si la ROM lleva; ahí no se reserva nada. */
    private static Range sramWindow;

    public static void setSramWindow(Range window) {
        sramWindow = window;
    }

    /** Si lo reservado cayera donde luego va a estar la SRAM, el juego leería la SRAM en vez de la ROM. */
    private static boolean hiddenBySram(int from, int size) {
        return sramWindow != null && from <= sramWindow.getTo() && sramWindow.getFrom() <= from + size - 1;
    }

    public static Integer getNewAddress(int size) {
        return getNewAddress(size, 0);
    }

    /**
     * Reserva espacio libre. Se coge el hueco más bajo donde quepa, para que el reparto no dependa del orden
     * en que estén guardados los rangos. Si bankSize no es cero, el bloque no cruzará ninguna frontera de ese
     * tamaño, porque el reproductor de samples direcciona la ROM por ventanas de banco.
     */
    public static Integer getNewAddress(int size, int bankSize) {
        List<Range> ranges = new ArrayList<>(App.config.spaceRanges());
        ranges.sort(Comparator.comparingInt(Range::getFrom));
        for (Range range : ranges) {
            // siempre en dirección par: por aquí pasan bloques comprimidos y samples que el 68000 lee a
            // palabras, y en dirección impar le da un error de dirección en cuanto los toca. Un byte de
            // más por reserva no se nota, y así el "+ 1" de abajo no deja el siguiente hueco impar.
            int from = range.getFrom() + (range.getFrom() & 1);
            // el bloque cruzaría un banco, se empieza en el siguiente
            if (bankSize > 0 && from % bankSize + size > bankSize) {
                from = (from / bankSize + 1) * bankSize;
            }
            if (from + size - 1 > range.getTo() || hiddenBySram(from, size)) {
                continue;
            }
            int next = from + size + 1;
            range.setFrom(next + (next & 1));
            if (range.getFrom() > range.getTo()) {
                App.config.spaceRanges().remove(range);
            }
            return from;
        }
        return null;
    }

    /**
     * Devuelve al espacio libre el hueco que deja algo que se ha movido de sitio, para que lo pueda
     * aprovechar lo siguiente que no quepa. Los huecos que quedan pegados a un rango que ya estaba se juntan
     * con él, para que no se pierda nada por el camino.
     * <p>
     * Sólo lo llama quien sabe que ahí ya no queda nada: un hueco compartido con otra cosa, como los samples
     * que se apuntan entre ellos, no se puede liberar.
     */
    public static void freeSpace(int from, int size) {
        if (size <= 0) {
            return;
        }
        int start = from;
        int end = from + size - 1;
        Iterator<Range> ranges = App.config.spaceRanges().iterator();
        while (ranges.hasNext()) {
            Range range = ranges.next();
            if (range.getTo() + 1 < start || end + 1 < range.getFrom()) {
                continue;
            }
            start = Math.min(start, range.getFrom());
            end = Math.max(end, range.getTo());
            ranges.remove();
        }
        App.config.spaceRanges().add(Range.of(start, end));
        Log.pnl("Queda libre el hueco de {0} a {1}, {2} bytes.",
                Integer.toHexString(start), Integer.toHexString(end), end - start + 1);
    }

    public static void writeThreeBytes(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >> 16) & 0xFF);
        data[offset + 1] = (byte) ((value >> 8) & 0xFF);
        data[offset + 2] = (byte) (value & 0xFF);
    }

    static int readWord(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    static void writeWord(byte[] data, int offset, int value) {
        data[offset] = (byte) (value >> 8);
        data[offset + 1] = (byte) value;
    }

}
