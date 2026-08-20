package net.krusher.mortalsdk;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.SwingConstants;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SampleEditor {

    private final File romFile;
    private final Config config;
    private final byte[] originalRom;
    private final DefaultListModel<SampleItem> model = new DefaultListModel<>();
    private final JList<SampleItem> list = new JList<>(model);
    private final Map<Integer, byte[]> replacements = new HashMap<>();
    private final JLabel details = new JLabel("Selecciona una muestra", SwingConstants.CENTER);
    private final JLabel status = new JLabel(" ");
    private Clip clip;

    public SampleEditor(File romFile, Config config) throws IOException {
        this.romFile = romFile;
        this.config = config;
        originalRom = Files.readAllBytes(romFile.toPath());
        List<SampleTableService.SampleEntry> entries = SampleTableService.readTable(
                originalRom, config.sampleTableOffset(), config.sampleCount());
        entries.stream()
                .filter(entry -> entry.length() > 0 && entry.isInside(originalRom.length))
                .map(SampleItem::new)
                .forEach(model::addElement);
    }

    public void show() {
        JFrame frame = new JFrame("MortalSDK - Editor de samples");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                stopPlayback();
            }
        });
        frame.setMinimumSize(new Dimension(860, 560));

        list.setCellRenderer(new SampleRenderer());
        list.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                updateDetails();
            }
        });

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 10));
        JButton play = new JButton("Reproducir");
        JButton stop = new JButton("Detener");
        JButton replace = new JButton("Cambiar WAV...");
        JButton restore = new JButton("Restablecer");
        JButton save = new JButton("Generar ROM...");
        controls.add(play);
        controls.add(stop);
        controls.add(replace);
        controls.add(restore);
        controls.add(save);

        play.addActionListener(event -> runAction(this::playSelected));
        stop.addActionListener(event -> stopPlayback());
        replace.addActionListener(event -> runAction(() -> replaceSelected(frame)));
        restore.addActionListener(event -> restoreSelected());
        save.addActionListener(event -> runAction(() -> savePatchedRom(frame)));

        JPanel right = new JPanel(new BorderLayout());
        details.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 15));
        details.setBorder(BorderFactory.createEmptyBorder(30, 20, 20, 20));
        right.add(details, BorderLayout.CENTER);
        right.add(controls, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(list), right);
        split.setDividerLocation(390);
        frame.add(split, BorderLayout.CENTER);
        status.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        frame.add(status, BorderLayout.SOUTH);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        if (!model.isEmpty()) {
            list.setSelectedIndex(0);
        }
    }

    private void playSelected() throws Exception {
        SampleItem item = selected();
        stopPlayback();
        byte[] pcm = currentPcm(item);
        byte[] wavSamples = new byte[pcm.length];
        for (int i = 0; i < pcm.length; i++) {
            wavSamples[i] = (byte) ((pcm[i] & 0xff) ^ 0x80);
        }
        AudioFormat format = new AudioFormat(config.pcmSampleRate(), 8, 1, false, false);
        clip = AudioSystem.getClip();
        clip.open(format, wavSamples, 0, wavSamples.length);
        clip.start();
        status.setText("Reproduciendo ID " + item.idHex());
    }

    private void stopPlayback() {
        if (clip != null) {
            clip.stop();
            clip.close();
            clip = null;
        }
    }

    private void replaceSelected(Component parent) throws IOException {
        SampleItem item = selected();
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Audio WAV", "wav"));
        if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        byte[] pcm = WavService.readSigned8BitMono(chooser.getSelectedFile(), config.pcmSampleRate());
        if (pcm.length == 0 || pcm.length > 0xffff) {
            throw new IOException("El WAV debe contener entre 1 y 65535 muestras");
        }
        replacements.put(item.entry().id(), pcm);
        list.repaint();
        updateDetails();
        status.setText("WAV preparado para ID " + item.idHex() + ". Se recolocará al generar la ROM.");
    }

    private void restoreSelected() {
        SampleItem item = list.getSelectedValue();
        if (item == null) {
            return;
        }
        replacements.remove(item.entry().id());
        list.repaint();
        updateDetails();
        status.setText("Cambio descartado para ID " + item.idHex());
    }

    private void savePatchedRom(Component parent) throws IOException {
        if (replacements.isEmpty()) {
            throw new IOException("No hay samples modificados");
        }
        JFileChooser chooser = new JFileChooser(romFile.getParentFile());
        chooser.setSelectedFile(new File(romFile.getParentFile(), romFile.getName() + ".samples.bin"));
        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        if (romFile.getCanonicalFile().equals(chooser.getSelectedFile().getCanonicalFile())) {
            throw new IOException("La ROM de salida debe ser distinta de la ROM de entrada");
        }
        byte[] patched = originalRom.clone();
        List<SampleTableService.ReplacementResult> results = SampleTableService.applyReplacements(
                patched, config, replacements);
        Checksum.fixChecksum(patched);
        Files.write(chooser.getSelectedFile().toPath(), patched);
        StringBuilder message = new StringBuilder("ROM generada:\n")
                .append(chooser.getSelectedFile().getAbsolutePath()).append("\n\n");
        for (var result : results) {
            message.append(String.format("ID %02X: %06X/%d -> %06X/%d%n", result.id(),
                    result.oldOffset(), result.oldLength(), result.newOffset(), result.newLength()));
        }
        JOptionPane.showMessageDialog(parent, message.toString(), "Inyección terminada",
                JOptionPane.INFORMATION_MESSAGE);
        status.setText(results.size() + " muestra(s) inyectada(s) en una ROM nueva");
    }

    private SampleItem selected() throws IOException {
        SampleItem item = list.getSelectedValue();
        if (item == null) {
            throw new IOException("Selecciona una muestra");
        }
        return item;
    }

    private byte[] currentPcm(SampleItem item) {
        return replacements.getOrDefault(item.entry().id(), java.util.Arrays.copyOfRange(originalRom,
                item.entry().offset(), item.entry().offset() + item.entry().length()));
    }

    private void updateDetails() {
        SampleItem item = list.getSelectedValue();
        if (item == null) {
            return;
        }
        byte[] pcm = currentPcm(item);
        details.setText(String.format("<html><div style='text-align:center'>"
                        + "<h2>Sample ID %s</h2>Offset original: %06X<br>Longitud original: %d bytes<br>"
                        + "Flags: %04X<br>Frecuencia de escucha: %d Hz<br>Duración actual: %.3f s<br><br>%s</div></html>",
                item.idHex(), item.entry().offset(), item.entry().length(), item.entry().flags(),
                config.pcmSampleRate(), (double) pcm.length / config.pcmSampleRate(),
                replacements.containsKey(item.entry().id()) ? "MODIFICADO - se recolocará" : "Original"));
    }

    private void runAction(ThrowingAction action) {
        try {
            action.run();
        } catch (Exception e) {
            showError(null, e);
        }
    }

    public static void showError(Component parent, Exception error) {
        JOptionPane.showMessageDialog(parent, error.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
    }

    private record SampleItem(SampleTableService.SampleEntry entry) {
        String idHex() {
            return String.format("%02X", entry.id());
        }
    }

    private final class SampleRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> source, Object value, int index,
                                                       boolean selected, boolean focus) {
            SampleItem item = (SampleItem) value;
            String changed = replacements.containsKey(item.entry().id()) ? "  [MODIFICADO]" : "";
            String label = String.format("ID %s   %06X   %5d bytes%s", item.idHex(), item.entry().offset(),
                    currentPcm(item).length, changed);
            return super.getListCellRendererComponent(source, label, index, selected, focus);
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
