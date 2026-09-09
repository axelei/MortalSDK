package net.krusher.mortalsdk.fondos;

import net.krusher.mortalsdk.Log;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Editor de fondos de combate.
 * <p>
 * Arriba, el plano entero (128x32 celdas) con rejilla y el número de tile de cada celda; abajo, el banco de
 * tiles del escenario, numerado, con los que están libres marcados. Se pinta píxel a píxel con la paleta de la
 * celda, se cambia el tile que usa una celda (escribiendo su número o cogiéndolo del banco), se voltea, se
 * cambia la línea de paleta y la prioridad, y se editan los fotogramas de las animaciones. Todo se guarda en
 * la carpeta del escenario y de ahí lo lee {@link FondoService} para meterlo en la ROM.
 */
public final class FondoEditor {

    private static final Color FONDO = new Color(0x1E1E1E);
    private static final Color REJILLA = new Color(0x50FFFFFF, true);
    private static final Color TEXTO_TILE = new Color(0xB0FFFF80, true);
    private static final Color SELECCION = new Color(0xFFEE44);
    private static final Color TAPADO = new Color(0x80000000, true);

    private final File carpetaFondos;
    private final byte[] rom;
    private final File romFile;
    private Fondo fondo;
    private char plano = 'A';
    private int zoom = 4;
    private int color = 1;
    private int celdaSel = -1;
    private int slotSel = -1;
    private int animacionSel = -1;
    private int fotogramaSel = 0;
    private String herramienta = "lapiz";
    private boolean verRejilla = true;
    private boolean verNumeros = true;
    private boolean verTapadas = true;
    private boolean verOtroPlano = true;

    private final Deque<byte[]> pasos = new ArrayDeque<>();
    private final PanelPlano panelPlano = new PanelPlano();
    private final PanelBanco panelBanco = new PanelBanco();
    private final PanelPaleta panelPaleta = new PanelPaleta();
    private final JLabel estado = new JLabel(" ");
    private final JLabel infoCelda = new JLabel(" ");
    private JFrame ventana;

    public FondoEditor(File carpetaFondos, File romFile) throws IOException {
        this.carpetaFondos = carpetaFondos;
        this.romFile = romFile;
        this.rom = Files.readAllBytes(romFile.toPath());
        this.fondo = Fondo.cargar(carpetaFondos, Escenario.TODOS.get(0), rom);
    }

    // ------------------------------------------------------------------ ventana

    public void mostrar() {
        ventana = new JFrame("MortalSDK — editor de fondos");
        ventana.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        ventana.setLayout(new BorderLayout());
        ventana.add(barra(), BorderLayout.NORTH);

        JScrollPane arriba = new JScrollPane(panelPlano);
        arriba.getVerticalScrollBar().setUnitIncrement(16);
        arriba.getHorizontalScrollBar().setUnitIncrement(16);
        JScrollPane abajo = new JScrollPane(panelBanco);
        abajo.setBorder(BorderFactory.createTitledBorder("Banco de tiles del escenario"));

        JSplitPane partido = new JSplitPane(JSplitPane.VERTICAL_SPLIT, arriba, abajo);
        partido.setResizeWeight(0.72);
        JPanel centro = new JPanel(new BorderLayout());
        centro.add(partido, BorderLayout.CENTER);
        centro.add(panelPaleta, BorderLayout.EAST);
        ventana.add(centro, BorderLayout.CENTER);

        JPanel pie = new JPanel(new BorderLayout());
        pie.add(estado, BorderLayout.WEST);
        pie.add(infoCelda, BorderLayout.EAST);
        pie.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        ventana.add(pie, BorderLayout.SOUTH);

        atajos();
        actualizarEstado();
        ventana.setSize(1500, 950);
        ventana.setLocationRelativeTo(null);
        ventana.setVisible(true);
    }

    private JPanel barra() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));

        JComboBox<String> escenarios = new JComboBox<>();
        for (Escenario e : Escenario.TODOS) {
            escenarios.addItem(e.numero() + " — " + e.nombre().replace('_', ' '));
        }
        escenarios.addActionListener(a -> cambiarEscenario(escenarios.getSelectedIndex()));
        p.add(new JLabel("Escenario:"));
        p.add(escenarios);

        ButtonGroup planos = new ButtonGroup();
        for (char c : new char[]{'A', 'B'}) {
            JToggleButton b = new JToggleButton("Plano " + c, c == 'A');
            b.addActionListener(a -> { plano = c; celdaSel = -1; repintar(); });
            planos.add(b);
            p.add(b);
        }

        p.add(Box.createHorizontalStrut(8));
        ButtonGroup herramientas = new ButtonGroup();
        String[][] tools = {{"lapiz", "Lápiz (B)"}, {"relleno", "Relleno (G)"}, {"cuentagotas", "Cuentagotas (I)"},
                {"celda", "Celda (C)"}};
        for (String[] t : tools) {
            JToggleButton b = new JToggleButton(t[1], t[0].equals("lapiz"));
            b.addActionListener(a -> { herramienta = t[0]; actualizarEstado(); });
            herramientas.add(b);
            p.add(b);
        }

        p.add(Box.createHorizontalStrut(8));
        p.add(boton("Zoom +", a -> { zoom = Math.min(12, zoom + 1); repintar(); }));
        p.add(boton("Zoom −", a -> { zoom = Math.max(1, zoom - 1); repintar(); }));

        JCheckBox rejilla = new JCheckBox("Rejilla", true);
        rejilla.addActionListener(a -> { verRejilla = rejilla.isSelected(); repintar(); });
        JCheckBox numeros = new JCheckBox("Nº de tile", true);
        numeros.addActionListener(a -> { verNumeros = numeros.isSelected(); repintar(); });
        JCheckBox tapadas = new JCheckBox("Marcar tapadas", true);
        tapadas.addActionListener(a -> { verTapadas = tapadas.isSelected(); repintar(); });
        JCheckBox otro = new JCheckBox("Ver el otro plano", true);
        otro.addActionListener(a -> { verOtroPlano = otro.isSelected(); repintar(); });
        p.add(rejilla);
        p.add(numeros);
        p.add(tapadas);
        p.add(otro);

        p.add(Box.createHorizontalStrut(8));
        p.add(boton("Guardar", a -> guardar()));
        p.add(boton("Compactar", a -> compactar()));
        p.add(boton("Animaciones…", a -> editarAnimacion()));
        return p;
    }

    private static JButton boton(String texto, java.awt.event.ActionListener accion) {
        JButton b = new JButton(texto);
        b.addActionListener(accion);
        return b;
    }

    private void atajos() {
        JPanel raiz = (JPanel) ventana.getContentPane();
        atajo(raiz, KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), "deshacer", a -> deshacer());
        atajo(raiz, KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), "guardar", a -> guardar());
        atajo(raiz, KeyStroke.getKeyStroke('b'), "lapiz", a -> { herramienta = "lapiz"; actualizarEstado(); });
        atajo(raiz, KeyStroke.getKeyStroke('g'), "relleno", a -> { herramienta = "relleno"; actualizarEstado(); });
        atajo(raiz, KeyStroke.getKeyStroke('i'), "cuentagotas", a -> { herramienta = "cuentagotas"; actualizarEstado(); });
        atajo(raiz, KeyStroke.getKeyStroke('c'), "celda", a -> { herramienta = "celda"; actualizarEstado(); });
        atajo(raiz, KeyStroke.getKeyStroke('h'), "volteoH", a -> voltear(true));
        atajo(raiz, KeyStroke.getKeyStroke('v'), "volteoV", a -> voltear(false));
    }

    private void atajo(JPanel raiz, KeyStroke tecla, String nombre, java.awt.event.ActionListener accion) {
        raiz.getInputMap(JPanel.WHEN_IN_FOCUSED_WINDOW).put(tecla, nombre);
        raiz.getActionMap().put(nombre, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { accion.actionPerformed(e); }
        });
    }

    // ------------------------------------------------------------------ acciones

    private void cambiarEscenario(int n) {
        try {
            guardarPaso();
            fondo = Fondo.cargar(carpetaFondos, Escenario.TODOS.get(n), rom);
            celdaSel = -1;
            slotSel = -1;
            animacionSel = -1;
            pasos.clear();
            repintar();
        } catch (IOException e) {
            error(e);
        }
    }

    private void guardar() {
        try {
            fondo.guardar();
            aviso("Guardado en " + fondo.carpeta.getName());
        } catch (IOException e) {
            error(e);
        }
    }

    private void compactar() {
        guardarPaso();
        int liberados = fondo.compactar();
        aviso("Tiles repetidos juntados: " + liberados + " slots libres más");
        repintar();
    }

    private void voltear(boolean horizontal) {
        if (celdaSel < 0) {
            return;
        }
        guardarPaso();
        int[] mapa = fondo.mapa(plano);
        mapa[celdaSel] ^= horizontal ? 0x0800 : 0x1000;
        repintar();
    }

    private void editarAnimacion() {
        List<Escenario.Animacion> anims = fondo.escenario.animaciones();
        if (anims.isEmpty()) {
            aviso("Este escenario no tiene animaciones de fondo.");
            return;
        }
        String[] nombres = new String[anims.size() + 1];
        nombres[0] = "(ninguna: editar el fondo)";
        for (int i = 0; i < anims.size(); i++) {
            nombres[i + 1] = anims.get(i).nombre() + " — " + anims.get(i).fotogramas() + " fotogramas, "
                    + anims.get(i).tilesPorFotograma() + " tiles";
        }
        Object elegido = JOptionPane.showInputDialog(ventana, "¿Qué animación editas?", "Animaciones",
                JOptionPane.QUESTION_MESSAGE, null, nombres, nombres[Math.max(0, animacionSel + 1)]);
        if (elegido == null) {
            return;
        }
        int idx = List.of(nombres).indexOf(elegido) - 1;
        animacionSel = idx;
        fotogramaSel = 0;
        repintar();
    }

    private void guardarPaso() {
        pasos.push(instantanea());
        while (pasos.size() > 60) {
            pasos.removeLast();
        }
    }

    private byte[] instantanea() {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int[] t : fondo.tiles) {
            out.writeBytes(Fondo.empaquetar(t));
        }
        out.writeBytes(FondoService.mapaRelativo(fondo.mapaA, 0));
        out.writeBytes(FondoService.mapaRelativo(fondo.mapaB, 0));
        for (int c : fondo.cram) {
            out.write(c >> 8);
            out.write(c & 0xFF);
        }
        for (int[][][] anim : fondo.fotogramas) {
            for (int[][] f : anim) {
                for (int[] t : f) {
                    out.writeBytes(Fondo.empaquetar(t));
                }
            }
        }
        return out.toByteArray();
    }

    private void deshacer() {
        if (pasos.isEmpty()) {
            return;
        }
        byte[] snap = pasos.pop();
        int at = 0;
        for (int[] t : fondo.tiles) {
            int[] px = Fondo.tileDe(snap, at);
            System.arraycopy(px, 0, t, 0, px.length);
            at += 32;
        }
        for (int i = 0; i < Escenario.CELDAS; i++) {
            fondo.mapaA[i] = Fondo.palabra(snap, at + i * 2);
        }
        at += Escenario.CELDAS * 2;
        for (int i = 0; i < Escenario.CELDAS; i++) {
            fondo.mapaB[i] = Fondo.palabra(snap, at + i * 2);
        }
        at += Escenario.CELDAS * 2;
        for (int i = 0; i < 64; i++) {
            fondo.cram[i] = Fondo.palabra(snap, at + i * 2);
        }
        at += 128;
        for (int[][][] anim : fondo.fotogramas) {
            for (int[][] f : anim) {
                for (int[] t : f) {
                    int[] px = Fondo.tileDe(snap, at);
                    System.arraycopy(px, 0, t, 0, px.length);
                    at += 32;
                }
            }
        }
        repintar();
    }

    private void repintar() {
        panelPlano.revalidate();
        panelPlano.repaint();
        panelBanco.revalidate();
        panelBanco.repaint();
        panelPaleta.repaint();
        actualizarEstado();
    }

    private void actualizarEstado() {
        int usados = 0;
        for (int u : fondo.usoTiles()) {
            if (u > 0) {
                usados++;
            }
        }
        estado.setText(String.format("  %s · %d tiles (%d en uso, %d libres) de %d · herramienta: %s · color %d",
                fondo.escenario.nombre().replace('_', ' '), fondo.tiles.length, usados, fondo.slotsLibres().size(),
                fondo.escenario.presupuestoTiles(), herramienta, color));
    }

    private void aviso(String texto) {
        JOptionPane.showMessageDialog(ventana, texto, "MortalSDK", JOptionPane.INFORMATION_MESSAGE);
    }

    private void error(Exception e) {
        Log.pnl("Error en el editor: {0}", String.valueOf(e.getMessage()));
        JOptionPane.showMessageDialog(ventana, String.valueOf(e.getMessage()), "Error", JOptionPane.ERROR_MESSAGE);
    }

    // ------------------------------------------------------------------ panel del plano

    private final class PanelPlano extends JPanel {
        PanelPlano() {
            setBackground(FONDO);
            MouseAdapter raton = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { pinchar(e, true); }
                @Override public void mouseDragged(MouseEvent e) { pinchar(e, false); }
                @Override public void mouseMoved(MouseEvent e) { informar(e); }
                @Override public void mouseWheelMoved(MouseWheelEvent e) {
                    if (e.isControlDown()) {
                        zoom = Math.max(1, Math.min(12, zoom - e.getWheelRotation()));
                        repintar();
                    } else {
                        getParent().dispatchEvent(e);
                    }
                }
            };
            addMouseListener(raton);
            addMouseMotionListener(raton);
            addMouseWheelListener(raton);
        }

        @Override public Dimension getPreferredSize() {
            if (animacionSel >= 0) {
                Escenario.Animacion a = fondo.escenario.animaciones().get(animacionSel);
                return new Dimension(a.tilesPorFotograma() * Fondo.TILE * zoom * 3 + 40,
                        a.fotogramas() * Fondo.TILE * zoom * 3 + 40);
            }
            return new Dimension(Escenario.ANCHO * Fondo.TILE * zoom, Escenario.ALTO * Fondo.TILE * zoom);
        }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            if (animacionSel >= 0) {
                pintarAnimacion(g);
                return;
            }
            int lado = Fondo.TILE * zoom;
            if (verOtroPlano) {
                g.drawImage(imagen(plano == 'A' ? 'B' : 'A'), 0, 0, Escenario.ANCHO * lado, Escenario.ALTO * lado, null);
            }
            g.drawImage(imagen(plano), 0, 0, Escenario.ANCHO * lado, Escenario.ALTO * lado, null);

            int[] mapa = fondo.mapa(plano);
            boolean[] visible = fondo.visible(plano);
            if (verTapadas) {
                g.setColor(TAPADO);
                for (int c = 0; c < Escenario.CELDAS; c++) {
                    if (!visible[c]) {
                        g.fillRect((c % Escenario.ANCHO) * lado, (c / Escenario.ANCHO) * lado, lado, lado);
                    }
                }
            }
            if (verRejilla && zoom >= 2) {
                g.setColor(REJILLA);
                g.setStroke(new BasicStroke(1));
                for (int x = 0; x <= Escenario.ANCHO; x++) {
                    g.drawLine(x * lado, 0, x * lado, Escenario.ALTO * lado);
                }
                for (int y = 0; y <= Escenario.ALTO; y++) {
                    g.drawLine(0, y * lado, Escenario.ANCHO * lado, y * lado);
                }
            }
            if (verNumeros && zoom >= 3) {
                g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, Math.max(7, Math.min(12, lado / 3))));
                g.setColor(TEXTO_TILE);
                for (int c = 0; c < Escenario.CELDAS; c++) {
                    int slot = fondo.slotDe(mapa[c]);
                    String txt = slot >= 0 ? Integer.toString(slot) : "·";
                    g.drawString(txt, (c % Escenario.ANCHO) * lado + 1, (c / Escenario.ANCHO) * lado + g.getFont().getSize());
                }
            }
            // dónde más se usa el tile que hay elegido en el banco
            if (slotSel >= 0) {
                g.setColor(new Color(0x66FF66));
                g.setStroke(new BasicStroke(1));
                int indice = fondo.escenario.baseTiles() + slotSel;
                for (int c = 0; c < Escenario.CELDAS; c++) {
                    if (Fondo.indice(mapa[c]) == indice) {
                        g.drawRect((c % Escenario.ANCHO) * lado + 1, (c / Escenario.ANCHO) * lado + 1, lado - 2, lado - 2);
                    }
                }
            }
            if (celdaSel >= 0) {
                g.setColor(SELECCION);
                g.setStroke(new BasicStroke(2));
                g.drawRect((celdaSel % Escenario.ANCHO) * lado, (celdaSel / Escenario.ANCHO) * lado, lado, lado);
            }
        }

        private void pintarAnimacion(Graphics2D g) {
            Escenario.Animacion a = fondo.escenario.animaciones().get(animacionSel);
            int[][][] frames = fondo.fotogramas.get(animacionSel);
            int lado = Fondo.TILE * zoom * 3;
            int[] pal = fondo.paletaCompleta();
            for (int f = 0; f < a.fotogramas(); f++) {
                for (int i = 0; i < a.tilesPorFotograma(); i++) {
                    BufferedImage im = new BufferedImage(Fondo.TILE, Fondo.TILE, BufferedImage.TYPE_INT_RGB);
                    for (int y = 0; y < Fondo.TILE; y++) {
                        for (int x = 0; x < Fondo.TILE; x++) {
                            im.setRGB(x, y, pal[a.linea() * 16 + frames[f][i][y * Fondo.TILE + x]]);
                        }
                    }
                    g.drawImage(im, 20 + i * lado, 20 + f * lado, lado, lado, null);
                }
            }
            g.setColor(REJILLA);
            for (int i = 0; i <= a.tilesPorFotograma(); i++) {
                g.drawLine(20 + i * lado, 20, 20 + i * lado, 20 + a.fotogramas() * lado);
            }
            for (int f = 0; f <= a.fotogramas(); f++) {
                g.drawLine(20, 20 + f * lado, 20 + a.tilesPorFotograma() * lado, 20 + f * lado);
            }
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            for (int f = 0; f < a.fotogramas(); f++) {
                g.drawString("f" + f, 4, 20 + f * lado + 12);
            }
        }

        private void pinchar(MouseEvent e, boolean nuevo) {
            if (animacionSel >= 0) {
                pincharAnimacion(e, nuevo);
                return;
            }
            int lado = Fondo.TILE * zoom;
            int col = e.getX() / lado, fila = e.getY() / lado;
            if (col < 0 || col >= Escenario.ANCHO || fila < 0 || fila >= Escenario.ALTO) {
                return;
            }
            int celda = fila * Escenario.ANCHO + col;
            int px = (e.getX() % lado) / zoom, py = (e.getY() % lado) / zoom;
            int[] mapa = fondo.mapa(plano);
            celdaSel = celda;
            if (SwingUtilities.isRightMouseButton(e) || herramienta.equals("cuentagotas")) {
                color = fondo.pixel(mapa, celda, px, py);
                actualizarEstado();
                repintar();
                return;
            }
            if (herramienta.equals("celda")) {
                if (nuevo) {
                    dialogoCelda(celda);
                }
                return;
            }
            int slot = fondo.slotDe(mapa[celda]);
            if (slot < 0) {
                aviso("Esa celda usa un tile de fuera del fondo (marcador o sprites): no se toca.");
                return;
            }
            if (fondo.tocado[slot]) {
                aviso("El tile " + slot + " lo reescribe el juego en marcha (animación). Edítalo desde «Animaciones…».");
                return;
            }
            if (nuevo) {
                guardarPaso();
            }
            int sx = Fondo.volteoH(mapa[celda]) ? Fondo.TILE - 1 - px : px;
            int sy = Fondo.volteoV(mapa[celda]) ? Fondo.TILE - 1 - py : py;
            if (herramienta.equals("relleno")) {
                relleno(fondo.tiles[slot], sx, sy, color);
            } else {
                fondo.tiles[slot][sy * Fondo.TILE + sx] = color;
            }
            slotSel = slot;
            repintar();
        }

        private void pincharAnimacion(MouseEvent e, boolean nuevo) {
            Escenario.Animacion a = fondo.escenario.animaciones().get(animacionSel);
            int lado = Fondo.TILE * zoom * 3, escala = zoom * 3;
            int i = (e.getX() - 20) / lado, f = (e.getY() - 20) / lado;
            if (i < 0 || i >= a.tilesPorFotograma() || f < 0 || f >= a.fotogramas()) {
                return;
            }
            int px = ((e.getX() - 20) % lado) / escala, py = ((e.getY() - 20) % lado) / escala;
            int[] tile = fondo.fotogramas.get(animacionSel)[f][i];
            if (SwingUtilities.isRightMouseButton(e) || herramienta.equals("cuentagotas")) {
                color = tile[py * Fondo.TILE + px];
                actualizarEstado();
                return;
            }
            if (nuevo) {
                guardarPaso();
            }
            if (herramienta.equals("relleno")) {
                relleno(tile, px, py, color);
            } else {
                tile[py * Fondo.TILE + px] = color;
            }
            fotogramaSel = f;
            repintar();
        }

        private void informar(MouseEvent e) {
            if (animacionSel >= 0) {
                return;
            }
            int lado = Fondo.TILE * zoom;
            int col = e.getX() / lado, fila = e.getY() / lado;
            if (col < 0 || col >= Escenario.ANCHO || fila < 0 || fila >= Escenario.ALTO) {
                infoCelda.setText(" ");
                return;
            }
            int celda = fila * Escenario.ANCHO + col;
            int w = fondo.mapa(plano)[celda];
            int slot = fondo.slotDe(w);
            int[] uso = fondo.usoTiles();
            infoCelda.setText(String.format("celda (%d,%d) · palabra %04X · tile %s · índice VRAM %03X · línea %d%s%s%s · %s  ",
                    fila, col, w, slot >= 0 ? Integer.toString(slot) : "fuera del fondo", Fondo.indice(w), Fondo.linea(w),
                    Fondo.volteoH(w) ? " ↔" : "", Fondo.volteoV(w) ? " ↕" : "", Fondo.prioridad(w) ? " delante" : "",
                    slot >= 0 ? "lo usan " + uso[slot] + " celdas" : ""));
        }
    }

    private void dialogoCelda(int celda) {
        int[] mapa = fondo.mapa(plano);
        int w = mapa[celda];
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        javax.swing.JTextField numero = new javax.swing.JTextField(String.valueOf(fondo.slotDe(w)));
        JCheckBox h = new JCheckBox("Volteo horizontal", Fondo.volteoH(w));
        JCheckBox v = new JCheckBox("Volteo vertical", Fondo.volteoV(w));
        JCheckBox prio = new JCheckBox("Delante del otro plano (prioridad)", Fondo.prioridad(w));
        JComboBox<String> linea = new JComboBox<>(new String[]{"0", "1", "2", "3"});
        linea.setSelectedIndex(Fondo.linea(w));
        p.add(new JLabel("Nº de tile del banco (o -1 para dejarlo como está):"));
        p.add(numero);
        p.add(h);
        p.add(v);
        p.add(prio);
        p.add(new JLabel("Línea de paleta:"));
        p.add(linea);
        JButton separar = new JButton("Darle un tile propio (copia)");
        separar.addActionListener(a -> {
            guardarPaso();
            int nuevo = fondo.separarTile(plano, celda);
            if (nuevo < 0) {
                aviso("No queda sitio para otro tile.");
            } else {
                numero.setText(String.valueOf(nuevo));
                repintar();
            }
        });
        p.add(separar);
        if (JOptionPane.showConfirmDialog(ventana, p, "Celda " + (celda / Escenario.ANCHO) + "," + (celda % Escenario.ANCHO),
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }
        guardarPaso();
        int slot;
        try {
            slot = Integer.parseInt(numero.getText().trim());
        } catch (NumberFormatException e) {
            slot = -1;
        }
        int indice = slot >= 0 && slot < fondo.tiles.length ? fondo.escenario.baseTiles() + slot : Fondo.indice(mapa[celda]);
        mapa[celda] = Fondo.palabraDe(indice, h.isSelected(), v.isSelected(), linea.getSelectedIndex(), prio.isSelected());
        repintar();
    }

    private static void relleno(int[] tile, int x, int y, int color) {
        int viejo = tile[y * Fondo.TILE + x];
        if (viejo == color) {
            return;
        }
        Deque<Point> cola = new ArrayDeque<>();
        cola.push(new Point(x, y));
        while (!cola.isEmpty()) {
            Point pt = cola.pop();
            if (pt.x < 0 || pt.x >= Fondo.TILE || pt.y < 0 || pt.y >= Fondo.TILE
                    || tile[pt.y * Fondo.TILE + pt.x] != viejo) {
                continue;
            }
            tile[pt.y * Fondo.TILE + pt.x] = color;
            cola.push(new Point(pt.x + 1, pt.y));
            cola.push(new Point(pt.x - 1, pt.y));
            cola.push(new Point(pt.x, pt.y + 1));
            cola.push(new Point(pt.x, pt.y - 1));
        }
    }

    private BufferedImage imagen(char cual) {
        int ancho = Escenario.ANCHO * Fondo.TILE, alto = Escenario.ALTO * Fondo.TILE;
        BufferedImage im = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_ARGB);
        im.setRGB(0, 0, ancho, alto, fondo.renderArgb(cual), 0, ancho);
        return im;
    }

    // ------------------------------------------------------------------ banco de tiles

    private final class PanelBanco extends JPanel {
        private static final int COLS = 32;

        PanelBanco() {
            setBackground(FONDO);
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    int lado = Fondo.TILE * 3 + 14;
                    int slot = (e.getY() / lado) * COLS + e.getX() / lado;
                    if (slot < 0 || slot >= fondo.tiles.length) {
                        return;
                    }
                    slotSel = slot;
                    if (e.getClickCount() == 2 && celdaSel >= 0) {
                        guardarPaso();
                        int[] mapa = fondo.mapa(plano);
                        mapa[celdaSel] = (mapa[celdaSel] & 0xF800) | (fondo.escenario.baseTiles() + slot);
                    }
                    repintar();
                }
            });
        }

        @Override public Dimension getPreferredSize() {
            int lado = Fondo.TILE * 3 + 14;
            return new Dimension(COLS * lado, ((fondo.tiles.length + COLS - 1) / COLS) * lado + 4);
        }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            int lado = Fondo.TILE * 3 + 14;
            int[] uso = fondo.usoTiles();
            int[] pal = fondo.paletaCompleta();
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 9));
            for (int i = 0; i < fondo.tiles.length; i++) {
                int x = (i % COLS) * lado, y = (i / COLS) * lado;
                BufferedImage im = new BufferedImage(Fondo.TILE, Fondo.TILE, BufferedImage.TYPE_INT_RGB);
                for (int py = 0; py < Fondo.TILE; py++) {
                    for (int px = 0; px < Fondo.TILE; px++) {
                        im.setRGB(px, py, pal[3 * 16 + fondo.tiles[i][py * Fondo.TILE + px]]);
                    }
                }
                g.drawImage(im, x + 1, y + 11, Fondo.TILE * 3, Fondo.TILE * 3, null);
                g.setColor(uso[i] == 0 ? new Color(0x66FF66) : (fondo.tocado[i] ? new Color(0xFF9955) : new Color(0xCCCCCC)));
                g.drawString(Integer.toString(i), x + 1, y + 9);
                if (i == slotSel) {
                    g.setColor(SELECCION);
                    g.setStroke(new BasicStroke(2));
                    g.drawRect(x, y + 10, Fondo.TILE * 3 + 2, Fondo.TILE * 3 + 2);
                }
            }
        }
    }

    // ------------------------------------------------------------------ paleta

    private final class PanelPaleta extends JPanel {
        PanelPaleta() {
            setBackground(FONDO);
            setPreferredSize(new Dimension(150, 0));
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    int i = (e.getY() - 24) / 26;
                    if (i < 0 || i >= 16) {
                        return;
                    }
                    if (e.getClickCount() == 2) {
                        int linea = lineaActual();
                        Color inicial = new Color(Fondo.cramARgb(fondo.cram[linea * 16 + i]));
                        Color nuevo = javax.swing.JColorChooser.showDialog(ventana, "Color " + i + " de la línea " + linea, inicial);
                        if (nuevo != null) {
                            guardarPaso();
                            fondo.cram[linea * 16 + i] = Fondo.rgbACram(nuevo.getRGB());
                            repintar();
                        }
                    } else {
                        color = i;
                        actualizarEstado();
                        repaint();
                    }
                }
            });
        }

        private int lineaActual() {
            if (animacionSel >= 0) {
                return fondo.escenario.animaciones().get(animacionSel).linea();
            }
            return celdaSel >= 0 ? Fondo.linea(fondo.mapa(plano)[celdaSel]) : 3;
        }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            int linea = lineaActual();
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            g.drawString("Paleta línea " + linea, 8, 16);
            for (int i = 0; i < 16; i++) {
                int y = 24 + i * 26;
                g.setColor(new Color(Fondo.cramARgb(fondo.cram[linea * 16 + i])));
                g.fillRect(8, y, 60, 22);
                g.setColor(i == color ? SELECCION : Color.DARK_GRAY);
                g.setStroke(new BasicStroke(i == color ? 3 : 1));
                g.drawRect(8, y, 60, 22);
                g.setColor(Color.LIGHT_GRAY);
                g.drawString(i + (i == 0 ? " (transp.)" : ""), 74, y + 16);
            }
            g.setColor(Color.GRAY);
            g.drawString("doble clic = cambiar", 8, 24 + 16 * 26 + 16);
        }
    }

    // ------------------------------------------------------------------ arranque

    public static void abrir(File carpetaFondos, File rom, String capturarEn) {
        SwingUtilities.invokeLater(() -> {
            try {
                FondoEditor editor = new FondoEditor(carpetaFondos, rom);
                editor.mostrar();
                if (capturarEn != null) {
                    new Timer(1500, e -> {
                        ((Timer) e.getSource()).stop();
                        try {
                            BufferedImage im = new BufferedImage(editor.ventana.getWidth(), editor.ventana.getHeight(),
                                    BufferedImage.TYPE_INT_RGB);
                            editor.ventana.paint(im.getGraphics());
                            net.krusher.mortalsdk.Bitmap bmp = capturaABitmap(im);
                            net.krusher.mortalsdk.Png.write(bmp, new File(capturarEn));
                            Log.pnl("Captura del editor escrita en {0}", capturarEn);
                        } catch (IOException ex) {
                            Log.pnl("No se pudo capturar: {0}", String.valueOf(ex.getMessage()));
                        }
                        editor.ventana.dispose();
                        System.exit(0);
                    }).start();
                }
            } catch (IOException e) {
                Log.pnl("No se pudo abrir el editor: {0}", String.valueOf(e.getMessage()));
                System.exit(1);
            }
        });
    }

    /** Reduce la captura a 256 colores para poder escribirla con el Png del SDK. */
    private static net.krusher.mortalsdk.Bitmap capturaABitmap(BufferedImage im) {
        List<Integer> paleta = new ArrayList<>();
        java.util.Map<Integer, Integer> conocidos = new java.util.HashMap<>();
        int[][] cache = new int[im.getWidth()][im.getHeight()];
        for (int y = 0; y < im.getHeight(); y++) {
            for (int x = 0; x < im.getWidth(); x++) {
                int rgb = im.getRGB(x, y) & 0xF0F0F0;
                Integer idx = conocidos.get(rgb);
                if (idx == null) {
                    if (paleta.size() < 256) {
                        paleta.add(rgb);
                        idx = paleta.size() - 1;
                    } else {
                        idx = masCercano(paleta, rgb);
                    }
                    conocidos.put(rgb, idx);
                }
                cache[x][y] = idx;
            }
        }
        int[] pal = new int[Math.max(1, paleta.size())];
        for (int i = 0; i < paleta.size(); i++) {
            pal[i] = 0xFF000000 | paleta.get(i);
        }
        net.krusher.mortalsdk.Bitmap bmp = net.krusher.mortalsdk.Bitmap.indexed(im.getWidth(), im.getHeight(), pal);
        for (int y = 0; y < im.getHeight(); y++) {
            for (int x = 0; x < im.getWidth(); x++) {
                bmp.setIndex(x, y, cache[x][y]);
            }
        }
        return bmp;
    }

    private static int masCercano(List<Integer> paleta, int rgb) {
        int mejor = 0, mejorD = Integer.MAX_VALUE;
        for (int i = 0; i < paleta.size(); i++) {
            int c = paleta.get(i);
            int d = Math.abs((c >> 16 & 0xFF) - (rgb >> 16 & 0xFF)) + Math.abs((c >> 8 & 0xFF) - (rgb >> 8 & 0xFF))
                    + Math.abs((c & 0xFF) - (rgb & 0xFF));
            if (d < mejorD) {
                mejorD = d;
                mejor = i;
            }
        }
        return mejor;
    }
}
