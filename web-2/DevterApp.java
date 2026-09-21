import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.*;
import javax.swing.plaf.FontUIResource;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.*;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * Дэвтэр — хичээлийн тэмдэглэл (Java Swing).
 * Ажиллуулах:  javac -encoding UTF-8 DevterApp.java   →   java DevterApp
 * эсвэл (Java 11+):  java DevterApp.java
 */
public class DevterApp {

    /* ===================== MODEL (Serializable) ===================== */
    static class Data implements Serializable {
        private static final long serialVersionUID = 1L;
        List<Course> courses = new ArrayList<>();
        List<Reminder> reminders = new ArrayList<>();
        String activeId;
    }
    static class Course implements Serializable {
        private static final long serialVersionUID = 1L;
        String id = UUID.randomUUID().toString();
        String name; int color;
        Map<String, List<Note>> notes = new LinkedHashMap<>();
        List<Tool> tools = new ArrayList<>();
        Course(String name, int color) {
            this.name = name; this.color = color;
            for (String t : TYPES) notes.put(t, new ArrayList<>());
        }
    }
    static class Note implements Serializable {
        private static final long serialVersionUID = 1L;
        String title = ""; long created = System.currentTimeMillis();
        List<Block> blocks = new ArrayList<>();
    }
    /** type: text | draw | image */
    static class Block implements Serializable {
        private static final long serialVersionUID = 1L;
        String type; String text = ""; String caption = "";
        byte[] base;      // image блокийн зураг (JPEG)
        byte[] overlay;   // зурсан давхарга (PNG)
        Block(String type) { this.type = type; }
    }
    static class Item implements Serializable {
        private static final long serialVersionUID = 1L;
        String title = "", text = ""; boolean done;
    }
    /** type: calc | timer | todo | formulas */
    static class Tool implements Serializable {
        private static final long serialVersionUID = 1L;
        String type; int minutes = 25;
        List<Item> items = new ArrayList<>(); List<String> hist = new ArrayList<>();
        Tool(String type) { this.type = type; }
    }
    static class Reminder implements Serializable {
        private static final long serialVersionUID = 1L;
        String title; String courseId; LocalDateTime at; String repeat = "none"; boolean done;
    }

    /* ===================== CONSTANTS / GLOBALS ===================== */
    static final String[] TYPES = {"lecture", "seminar", "lab"};
    static final Map<String, String> LABEL = Map.of("lecture", "Лекц", "seminar", "Семинар", "lab", "Лаборатори");
    static final Map<String, String> NEWL = Map.of("lecture", "Шинэ лекц", "seminar", "Шинэ семинар", "lab", "Шинэ лаборатори");
    static final Map<String, String> TOOLN = new LinkedHashMap<>();
    static {
        TOOLN.put("calc", "Тооцоолуур"); TOOLN.put("timer", "Цаг хэмжигч");
        TOOLN.put("todo", "Даалгаврын жагсаалт"); TOOLN.put("formulas", "Томьёоны сан");
    }
    static final int[] PALETTE = {0x2F5BEA, 0xD6303B, 0x1F8A57, 0xC25E0B, 0x7C4DDB, 0x0B8A8A, 0xC23A8A, 0x55647F};
    static final Color INK = new Color(0x1B2540), LINE = new Color(0xD6DEEC), BG = new Color(0xEDF1F8),
            MUTED = new Color(0x5B6987), RED = new Color(0xD6303B), YEL = new Color(0xFFD84D);
    static final Path DIR = Paths.get(System.getProperty("user.home"), ".devter");
    static final Path FILE = DIR.resolve("devter.dat");
    static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy.MM.dd  HH:mm");
    static final Map<Tool, TS> TIMERS = new IdentityHashMap<>();

    static Data data = new Data();
    static volatile boolean dirty;
    static JFrame frame; static JPanel content; static JList<Course> courseList;
    static DefaultListModel<Course> courseModel = new DefaultListModel<>();
    static JButton bellBtn; static JLabel status; static boolean updating;
    static CoursePanel currentPanel; static TrayIcon tray; static boolean trayHintShown;
    static JDialog remDlg; static JPanel remList;

    /* ===================== PERSISTENCE ===================== */
    static void load() {
        try (ObjectInputStream in = new ObjectInputStream(new BufferedInputStream(Files.newInputStream(FILE)))) {
            data = (Data) in.readObject();
        } catch (Exception e) { data = new Data(); }
    }
    static synchronized void saveNow() {
        try {
            Files.createDirectories(DIR);
            Path tmp = DIR.resolve("devter.tmp");
            try (ObjectOutputStream out = new ObjectOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
                out.writeObject(data);
            }
            Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
            if (status != null) status.setText("Хадгалсан " + java.time.LocalTime.now().withNano(0));
        } catch (Exception e) {
            if (status != null) status.setText("Хадгалж чадсангүй: " + e.getMessage());
        }
    }
    static void markDirty() { dirty = true; if (status != null) status.setText("Хадгалж байна…"); }

    /* ===================== HELPERS ===================== */
    static Color col(int rgb) { return new Color(rgb); }
    static byte[] png(BufferedImage im) {
        try { ByteArrayOutputStream o = new ByteArrayOutputStream(); ImageIO.write(im, "png", o); return o.toByteArray(); }
        catch (IOException e) { return null; }
    }
    static byte[] jpg(BufferedImage im) {
        try { ByteArrayOutputStream o = new ByteArrayOutputStream(); ImageIO.write(im, "jpg", o); return o.toByteArray(); }
        catch (IOException e) { return null; }
    }
    static BufferedImage img(byte[] b) {
        try { return b == null ? null : ImageIO.read(new ByteArrayInputStream(b)); } catch (IOException e) { return null; }
    }
    /** Зургийг 1000x900-д багтааж, RGB болгоно. */
    static BufferedImage fit(BufferedImage src) {
        double k = Math.min(1.0, Math.min(1000.0 / src.getWidth(), 900.0 / src.getHeight()));
        int w = Math.max(1, (int) Math.round(src.getWidth() * k)), h = Math.max(1, (int) Math.round(src.getHeight() * k));
        BufferedImage o = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = o.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, w, h);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(src, 0, 0, w, h, null); g.dispose();
        return o;
    }
    static BufferedImage clipboardImage() {
        try {
            Transferable t = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
            if (t != null && t.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                Image i = (Image) t.getTransferData(DataFlavor.imageFlavor);
                BufferedImage b = new BufferedImage(i.getWidth(null), i.getHeight(null), BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = b.createGraphics(); g.drawImage(i, 0, 0, null); g.dispose();
                return b;
            }
        } catch (Exception e) { /* ignore */ }
        return null;
    }
    static JButton button(String text, Runnable r) {
        JButton b = new JButton(text);
        b.setFocusPainted(false);
        if (r != null) b.addActionListener(e -> r.run());
        return b;
    }
    static DocumentListener dl(Runnable r) {
        return new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { r.run(); }
            public void removeUpdate(DocumentEvent e) { r.run(); }
            public void changedUpdate(DocumentEvent e) { r.run(); }
        };
    }
    static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
    static Course byId(String id) {
        for (Course c : data.courses) if (c.id.equals(id)) return c;
        return null;
    }
    static void setUIFont(Font f) {
        FontUIResource r = new FontUIResource(f);
        for (Object k : Collections.list(UIManager.getDefaults().keys()))
            if (UIManager.get(k) instanceof FontUIResource) UIManager.put(k, r);
    }

    /** BoxLayout-д тохирох, өндрөө өөрийн хэмжээнд барьдаг самбар. */
    static class Wrap extends JPanel {
        Wrap(LayoutManager l) { super(l); setAlignmentX(Component.LEFT_ALIGNMENT); }
        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
    }
    /** Өргөнөө viewport-д тааруулдаг босоо багана. */
    static class Column extends JPanel implements Scrollable {
        Column() { setLayout(new BoxLayout(this, BoxLayout.Y_AXIS)); }
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 20; }
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return Math.max(40, r.height - 40); }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }

    /* ===================== NOTIFICATIONS ===================== */
    static int openToasts = 0;
    static void notifyUser(String title, String body) {
        Toolkit.getDefaultToolkit().beep();
        try { if (tray != null) tray.displayMessage(title, body, TrayIcon.MessageType.INFO); } catch (Exception e) { /* ignore */ }
        showToast(title, body);
    }
    static void showToast(String title, String body) {
        try {
            JWindow w = new JWindow();
            JPanel p = new JPanel(new BorderLayout(0, 2));
            p.setBackground(INK); p.setBorder(new CompoundBorder(new MatteBorder(0, 6, 0, 0, col(PALETTE[0])), new EmptyBorder(12, 14, 12, 14)));
            JLabel t = new JLabel(title); t.setForeground(Color.WHITE); t.setFont(t.getFont().deriveFont(Font.BOLD, 15f));
            JLabel b = new JLabel(body == null ? "" : body); b.setForeground(new Color(0xC9D2EA));
            p.add(t, BorderLayout.NORTH); p.add(b, BorderLayout.CENTER);
            w.setContentPane(p); w.setAlwaysOnTop(true); w.pack();
            w.setSize(Math.max(320, w.getWidth()), w.getHeight());
            Rectangle scr = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            w.setLocation(scr.x + scr.width - w.getWidth() - 16, scr.y + scr.height - (w.getHeight() + 12) * (openToasts + 1) - 4);
            openToasts++;
            Runnable close = () -> { if (w.isDisplayable()) { w.dispose(); openToasts = Math.max(0, openToasts - 1); } };
            p.addMouseListener(new MouseAdapter() { public void mouseClicked(MouseEvent e) { close.run(); } });
            w.setVisible(true);
            javax.swing.Timer tm = new javax.swing.Timer(9000, e -> close.run()); tm.setRepeats(false); tm.start();
        } catch (Exception e) { /* headless */ }
    }

    /* ===================== MAIN WINDOW ===================== */
    public static void main(String[] args) { SwingUtilities.invokeLater(DevterApp::start); }

    static void start() {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception e) { /* ignore */ }
        setUIFont(new Font("SansSerif", Font.PLAIN, 14));
        load();
        buildFrame();
        setupTray();
        javax.swing.Timer tick = new javax.swing.Timer(1000, e -> tick()); tick.start();
        javax.swing.Timer auto = new javax.swing.Timer(3000, e -> { if (dirty) saveNow(); }); auto.start();
        frame.setVisible(true);
    }

    static void buildFrame() {
        frame = new JFrame("Дэвтэр — хичээлийн тэмдэглэл");
        frame.setSize(1240, 820); frame.setLocationRelativeTo(null);
        frame.setIconImage(appIcon(64));
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                saveNow();
                if (tray != null) {
                    frame.setVisible(false);
                    if (!trayHintShown) { trayHintShown = true; tray.displayMessage("Дэвтэр", "Сануулга ирэхийн тулд ар талд ажиллаж байна.", TrayIcon.MessageType.INFO); }
                } else System.exit(0);
            }
        });

        JPanel side = new JPanel(new BorderLayout(0, 10));
        side.setBorder(new EmptyBorder(14, 12, 12, 8)); side.setPreferredSize(new Dimension(250, 0)); side.setBackground(Color.WHITE);
        JPanel brand = new JPanel(new GridLayout(0, 1)); brand.setOpaque(false);
        JLabel h1 = new JLabel("Дэвтэр"); h1.setFont(h1.getFont().deriveFont(Font.BOLD, 30f));
        JLabel sub = new JLabel("Хичээлийн тэмдэглэл, хэрэгсэл, сануулга"); sub.setForeground(MUTED); sub.setFont(sub.getFont().deriveFont(12f));
        brand.add(h1); brand.add(sub); side.add(brand, BorderLayout.NORTH);

        courseList = new JList<>(courseModel);
        courseList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        courseList.setFixedCellHeight(40);
        courseList.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean foc) {
                JLabel r = (JLabel) super.getListCellRendererComponent(l, v, i, sel, foc);
                Course c = (Course) v;
                r.setText(c.name.isBlank() ? "Нэргүй хичээл" : c.name);
                r.setBorder(new CompoundBorder(new MatteBorder(0, 6, 0, 0, col(c.color)), new EmptyBorder(0, 10, 0, 6)));
                if (!sel) r.setBackground(Color.WHITE); else r.setBackground(new Color((c.color & 0xFFFFFF) | 0x28000000, true).brighter());
                r.setForeground(INK); r.setFont(r.getFont().deriveFont(Font.BOLD));
                return r;
            }
        });
        courseList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !updating) showCourse(courseList.getSelectedValue());
        });
        JScrollPane cs = new JScrollPane(courseList); cs.setBorder(new LineBorder(LINE));
        JPanel top = new JPanel(new BorderLayout(0, 4)); top.setOpaque(false);
        JLabel mine = new JLabel("Миний хичээлүүд"); mine.setForeground(MUTED); top.add(mine, BorderLayout.NORTH); top.add(cs, BorderLayout.CENTER);
        side.add(top, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new GridLayout(0, 1, 0, 6)); bottom.setOpaque(false);
        bottom.add(button("+  Хичээл нэмэх", DevterApp::addCourse));
        bellBtn = button("Сануулга", DevterApp::openReminders); bottom.add(bellBtn);
        status = new JLabel("Бэлэн"); status.setForeground(MUTED); status.setFont(status.getFont().deriveFont(12f)); bottom.add(status);
        side.add(bottom, BorderLayout.SOUTH);

        content = new JPanel(new BorderLayout()); content.setBackground(BG);
        frame.getContentPane().add(side, BorderLayout.WEST);
        frame.getContentPane().add(content, BorderLayout.CENTER);

        refreshCourses(byId(data.activeId) != null ? byId(data.activeId) : (data.courses.isEmpty() ? null : data.courses.get(0)));
        updateBell();
    }

    static void refreshCourses(Course select) {
        updating = true;
        courseModel.clear();
        for (Course c : data.courses) courseModel.addElement(c);
        if (select != null) courseList.setSelectedValue(select, true);
        updating = false;
        showCourse(select);
    }

    static void addCourse() {
        String name = JOptionPane.showInputDialog(frame, "Хичээлийн нэр:", "Хичээл нэмэх", JOptionPane.PLAIN_MESSAGE);
        if (name == null) return;
        Course c = new Course(name.isBlank() ? "Шинэ хичээл" : name.trim(), PALETTE[data.courses.size() % PALETTE.length]);
        data.courses.add(c); markDirty();
        refreshCourses(c);
    }

    static void showCourse(Course c) {
        content.removeAll(); currentPanel = null;
        if (c == null) {
            JPanel e = new JPanel(new GridBagLayout()); e.setOpaque(false);
            JPanel box = new JPanel(new GridLayout(0, 1, 0, 10)); box.setOpaque(false);
            JLabel t = new JLabel("Эхний хичээлээ нэмье", SwingConstants.CENTER); t.setFont(t.getFont().deriveFont(Font.BOLD, 28f));
            JLabel d = new JLabel("<html><div style='text-align:center;width:380px'>Хичээл бүрт лекц, семинар, лабораторийн тэмдэглэл, зураг, тооцоолуур зэрэг хэрэгслүүд тусдаа хадгалагдана.</div></html>", SwingConstants.CENTER);
            d.setForeground(MUTED);
            box.add(t); box.add(d); box.add(button("+  Хичээл нэмэх", DevterApp::addCourse));
            e.add(box); content.add(e, BorderLayout.CENTER);
        } else {
            data.activeId = c.id; markDirty();
            currentPanel = new CoursePanel(c);
            content.add(currentPanel, BorderLayout.CENTER);
        }
        content.revalidate(); content.repaint();
    }

    static void updateBell() {
        long n = data.reminders.stream().filter(r -> !r.done).count();
        if (bellBtn != null) bellBtn.setText("Сануулга" + (n > 0 ? "  (" + n + ")" : ""));
    }

    /* ===================== COURSE PANEL ===================== */
    static class CoursePanel extends JPanel {
        final Course c; final JTabbedPane tabs = new JTabbedPane();
        CoursePanel(Course c) {
            super(new BorderLayout(0, 8)); this.c = c;
            setBorder(new EmptyBorder(14, 18, 14, 18)); setOpaque(false);
            JPanel head = new JPanel(new BorderLayout(10, 0)); head.setOpaque(false);
            JTextField name = new JTextField(c.name);
            name.setFont(name.getFont().deriveFont(Font.BOLD, 26f)); name.setForeground(col(c.color));
            name.setBorder(new MatteBorder(0, 0, 2, 0, LINE)); name.setOpaque(false);
            name.getDocument().addDocumentListener(dl(() -> { c.name = name.getText(); courseList.repaint(); markDirty(); }));
            JPanel act = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0)); act.setOpaque(false);
            act.add(button("Өнгө…", () -> {
                Color n = JColorChooser.showDialog(frame, "Хичээлийн өнгө", col(c.color));
                if (n != null) { c.color = n.getRGB() & 0xFFFFFF; markDirty(); int t = tabs.getSelectedIndex(); showCourse(c); if (currentPanel != null) currentPanel.tabs.setSelectedIndex(t); courseList.repaint(); }
            }));
            act.add(button("Хичээл устгах", () -> {
                if (JOptionPane.showConfirmDialog(frame, "«" + c.name + "» хичээлийг бүх тэмдэглэлтэй нь устгах уу?", "Устгах", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION) {
                    data.courses.remove(c); data.reminders.removeIf(r -> c.id.equals(r.courseId)); markDirty(); updateBell();
                    refreshCourses(data.courses.isEmpty() ? null : data.courses.get(0));
                }
            }));
            head.add(name, BorderLayout.CENTER); head.add(act, BorderLayout.EAST);
            add(head, BorderLayout.NORTH);
            for (String t : TYPES) tabs.addTab(LABEL.get(t), new NotesPanel(c, t));
            tabs.addTab("Хэрэгсэл", new ToolsPanel(c));
            tabs.setFont(tabs.getFont().deriveFont(Font.BOLD, 14f));
            add(tabs, BorderLayout.CENTER);
        }
    }

    /* ===================== NOTES ===================== */
    static class NotesPanel extends JPanel {
        final Course c; final String type;
        final DefaultListModel<String> model = new DefaultListModel<>();
        final JList<String> list = new JList<>(model);
        final JScrollPane scroll = new JScrollPane();
        boolean upd;

        NotesPanel(Course c, String type) {
            super(new BorderLayout(10, 0)); this.c = c; this.type = type;
            setBorder(new EmptyBorder(10, 0, 0, 0));
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); list.setFixedCellHeight(34);
            JPanel left = new JPanel(new BorderLayout(0, 8)); left.setPreferredSize(new Dimension(210, 0));
            JScrollPane ls = new JScrollPane(list); ls.setBorder(new LineBorder(LINE));
            left.add(ls, BorderLayout.CENTER);
            left.add(button("+  " + NEWL.get(type), this::addNote), BorderLayout.SOUTH);
            add(left, BorderLayout.WEST);
            scroll.setBorder(new LineBorder(LINE)); scroll.getVerticalScrollBar().setUnitIncrement(18);
            scroll.getViewport().setBackground(Color.WHITE);
            add(scroll, BorderLayout.CENTER);
            list.addListSelectionListener(e -> { if (!e.getValueIsAdjusting() && !upd) showSelected(); });
            refresh(0);
        }
        List<Note> notes() { return c.notes.get(type); }
        String rowText(int i) {
            Note n = notes().get(i);
            return LABEL.get(type) + " " + (i + 1) + (n.title.isBlank() ? "" : "  —  " + n.title);
        }
        void refresh(int sel) {
            upd = true; model.clear();
            for (int i = 0; i < notes().size(); i++) model.addElement(rowText(i));
            if (!notes().isEmpty()) list.setSelectedIndex(Math.max(0, Math.min(sel, notes().size() - 1)));
            upd = false; showSelected();
        }
        void addNote() {
            Note n = new Note(); n.blocks.add(new Block("text"));
            notes().add(n); markDirty(); refresh(notes().size() - 1);
        }
        void showSelected() {
            int i = list.getSelectedIndex();
            if (i < 0 || i >= notes().size()) {
                JLabel l = new JLabel("<html><div style='text-align:center;width:360px;color:#5B6987'>Энд " + LABEL.get(type).toLowerCase()
                        + "ийн тэмдэглэл хараахан алга. Зүүн доорх «" + NEWL.get(type) + "» товчийг дарж эхлээрэй.</div></html>", SwingConstants.CENTER);
                scroll.setViewportView(l); return;
            }
            Note n = notes().get(i);
            scroll.setViewportView(new Editor(c, type, n, i,
                    () -> { upd = true; model.set(i, rowText(i)); upd = false; },
                    () -> {
                        if (JOptionPane.showConfirmDialog(frame, "Энэ тэмдэглэлийг устгах уу?", "Устгах", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                            notes().remove(n); markDirty(); refresh(Math.max(0, i - 1));
                        }
                    }));
            SwingUtilities.invokeLater(() -> scroll.getVerticalScrollBar().setValue(0));
        }
    }

    static class Editor extends Column {
        final Note n; final JPanel blocks = new JPanel();
        Editor(Course c, String type, Note n, int idx, Runnable titleChanged, Runnable onDelete) {
            this.n = n; setBackground(Color.WHITE); setBorder(new EmptyBorder(16, 22, 20, 22));
            JPanel head = new Wrap(new BorderLayout()); head.setOpaque(false);
            JPanel l = new JPanel(new GridLayout(0, 1)); l.setOpaque(false);
            JLabel t = new JLabel(LABEL.get(type) + " " + (idx + 1)); t.setFont(t.getFont().deriveFont(Font.BOLD, 32f)); t.setForeground(col(c.color));
            JLabel d = new JLabel(java.time.LocalDate.ofInstant(java.time.Instant.ofEpochMilli(n.created), java.time.ZoneId.systemDefault()).toString());
            d.setForeground(MUTED);
            l.add(t); l.add(d);
            head.add(l, BorderLayout.CENTER);
            JPanel r = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0)); r.setOpaque(false);
            r.add(button("Тэмдэглэл устгах", onDelete)); head.add(r, BorderLayout.EAST);
            add(head);
            add(Box.createVerticalStrut(8));

            JPanel tr = new Wrap(new BorderLayout(8, 0)); tr.setOpaque(false);
            tr.add(new JLabel("Сэдэв:"), BorderLayout.WEST);
            JTextField tf = new JTextField(n.title);
            tf.getDocument().addDocumentListener(dl(() -> { n.title = tf.getText(); markDirty(); titleChanged.run(); }));
            tr.add(tf, BorderLayout.CENTER);
            add(tr); add(Box.createVerticalStrut(14));

            blocks.setLayout(new BoxLayout(blocks, BoxLayout.Y_AXIS)); blocks.setOpaque(false); blocks.setAlignmentX(LEFT_ALIGNMENT);
            add(blocks); paintBlocks();

            JPanel add = new Wrap(new FlowLayout(FlowLayout.LEFT, 8, 0)); add.setOpaque(false);
            add.setBorder(new MatteBorder(1, 0, 0, 0, LINE));
            add.add(button("Бичвэр нэмэх", () -> addBlock(new Block("text"))));
            add.add(button("Зурах талбар нэмэх", () -> addBlock(new Block("draw"))));
            add.add(button("Зураг оруулах", () -> addBlock(new Block("image"))));
            add.add(button("Screenshot буулгах (Ctrl+V-ийн оронд)", () -> {
                BufferedImage im = clipboardImage();
                if (im == null) { JOptionPane.showMessageDialog(frame, "Clipboard дээр зураг алга. Эхлээд screenshot аваад (Win+Shift+S) дараа нь дахин дарна уу."); return; }
                Block b = new Block("image"); setBase(b, im); addBlock(b);
            }));
            add(Box.createVerticalStrut(6)); add(add);
            add(Box.createVerticalGlue());
        }
        void addBlock(Block b) { n.blocks.add(b); markDirty(); paintBlocks(); }
        void paintBlocks() {
            blocks.removeAll();
            for (Block b : n.blocks) blocks.add(blockView(n, b, this::paintBlocks));
            blocks.revalidate(); blocks.repaint();
        }
    }

    static JComponent blockView(Note n, Block b, Runnable repaintAll) {
        Wrap w = new Wrap(new BorderLayout(0, 4)); w.setOpaque(false);
        w.setBorder(new EmptyBorder(0, 0, 16, 0));
        JPanel ctl = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0)); ctl.setOpaque(false);
        JButton up = button("↑", () -> { int i = n.blocks.indexOf(b); if (i > 0) { Collections.swap(n.blocks, i, i - 1); markDirty(); repaintAll.run(); } });
        JButton dn = button("↓", () -> { int i = n.blocks.indexOf(b); if (i >= 0 && i < n.blocks.size() - 1) { Collections.swap(n.blocks, i, i + 1); markDirty(); repaintAll.run(); } });
        JButton del = button("✕", () -> {
            if (JOptionPane.showConfirmDialog(frame, "Энэ блокийг устгах уу?", "Устгах", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                n.blocks.remove(b); markDirty(); repaintAll.run();
            }
        });
        for (JButton x : new JButton[]{up, dn, del}) { x.setMargin(new Insets(0, 6, 0, 6)); x.setFont(x.getFont().deriveFont(11f)); ctl.add(x); }
        w.add(ctl, BorderLayout.NORTH);
        switch (b.type) {
            case "text": w.add(textView(b), BorderLayout.CENTER); break;
            case "draw": w.add(drawView(b), BorderLayout.CENTER); break;
            default: w.add(imageView(b, repaintAll), BorderLayout.CENTER);
        }
        return w;
    }

    static JComponent textView(Block b) {
        JTextArea ta = new JTextArea(b.text, 4, 40) {
            @Override protected void paintComponent(Graphics g) {
                g.setColor(new Color(0xE3E9F4));
                int lh = getFontMetrics(getFont()).getHeight();
                for (int y = getInsets().top + lh; y < getHeight(); y += lh) g.drawLine(0, y, getWidth(), y);
                super.paintComponent(g);
            }
        };
        ta.setLineWrap(true); ta.setWrapStyleWord(true); ta.setOpaque(false);
        ta.setFont(new Font("SansSerif", Font.PLAIN, 15)); ta.setBorder(new EmptyBorder(2, 6, 4, 6));
        ta.getDocument().addDocumentListener(dl(() -> { b.text = ta.getText(); markDirty(); ta.revalidate(); }));
        ta.addComponentListener(new ComponentAdapter() {
            int lastW = -1;
            public void componentResized(ComponentEvent e) { if (ta.getWidth() != lastW) { lastW = ta.getWidth(); ta.revalidate(); } }
        });
        return ta;
    }

    /* ===================== DRAWING ===================== */
    static class Sketch extends JComponent {
        final BufferedImage base, layer; final boolean grid; final Color defColor;
        boolean canDraw; String tool = "pen"; Color color; int size = 3; Runnable onChange;
        final Deque<BufferedImage> undo = new ArrayDeque<>();
        BufferedImage snap; final List<Point2D.Float> pts = new ArrayList<>();

        Sketch(BufferedImage base, int w, int h, byte[] overlay, boolean grid, Color pen, boolean canDraw) {
            this.base = base; this.grid = grid; this.color = pen; this.defColor = pen;
            layer = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            BufferedImage o = img(overlay);
            if (o != null) { Graphics2D g = layer.createGraphics(); g.drawImage(o, 0, 0, null); g.dispose(); }
            setDrawing(canDraw);
            MouseAdapter m = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    if (!Sketch.this.canDraw || !SwingUtilities.isLeftMouseButton(e)) return;
                    snap = copy(layer); undo.push(snap); if (undo.size() > 25) undo.removeLast();
                    pts.clear(); pts.add(new Point2D.Float(e.getX(), e.getY())); paintStroke();
                }
                @Override public void mouseDragged(MouseEvent e) {
                    if (snap == null) return;
                    pts.add(new Point2D.Float(e.getX(), e.getY())); paintStroke();
                }
                @Override public void mouseReleased(MouseEvent e) {
                    if (snap == null) return;
                    snap = null; if (onChange != null) onChange.run();
                }
            };
            addMouseListener(m); addMouseMotionListener(m);
        }
        void setDrawing(boolean v) { canDraw = v; setCursor(Cursor.getPredefinedCursor(v ? Cursor.CROSSHAIR_CURSOR : Cursor.DEFAULT_CURSOR)); }
        static BufferedImage copy(BufferedImage s) {
            BufferedImage c = new BufferedImage(s.getWidth(), s.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = c.createGraphics(); g.drawImage(s, 0, 0, null); g.dispose(); return c;
        }
        void paintStroke() {
            Graphics2D g = layer.createGraphics();
            g.setComposite(AlphaComposite.Src); g.drawImage(snap, 0, 0, null);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            float wpx = size;
            if (tool.equals("eraser")) { g.setComposite(AlphaComposite.Clear); wpx = size * 4; }
            else if (tool.equals("hl")) { g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.4f)); g.setColor(color); wpx = size * 4; }
            else { g.setComposite(AlphaComposite.SrcOver); g.setColor(color); }
            g.setStroke(new BasicStroke(wpx, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D.Float p = new Path2D.Float();
            Point2D.Float f = pts.get(0); p.moveTo(f.x, f.y);
            if (pts.size() == 1) p.lineTo(f.x + 0.01f, f.y);
            for (int i = 1; i < pts.size() - 1; i++) {
                Point2D.Float a = pts.get(i), b = pts.get(i + 1);
                p.quadTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2);
            }
            if (pts.size() > 1) { Point2D.Float l = pts.get(pts.size() - 1); p.lineTo(l.x, l.y); }
            g.draw(p); g.dispose(); repaint();
        }
        void undoLast() {
            BufferedImage s = undo.poll(); if (s == null) return;
            Graphics2D g = layer.createGraphics(); g.setComposite(AlphaComposite.Src); g.drawImage(s, 0, 0, null); g.dispose();
            repaint(); if (onChange != null) onChange.run();
        }
        void clearAll() {
            undo.push(copy(layer));
            Graphics2D g = layer.createGraphics(); g.setComposite(AlphaComposite.Clear); g.fillRect(0, 0, layer.getWidth(), layer.getHeight()); g.dispose();
            repaint(); if (onChange != null) onChange.run();
        }
        @Override public Dimension getPreferredSize() { return new Dimension(layer.getWidth(), layer.getHeight()); }
        @Override public Dimension getMinimumSize() { return getPreferredSize(); }
        @Override public Dimension getMaximumSize() { return getPreferredSize(); }
        @Override protected void paintComponent(Graphics gr) {
            Graphics2D g = (Graphics2D) gr.create();
            int w = layer.getWidth(), h = layer.getHeight();
            if (base != null) g.drawImage(base, 0, 0, null);
            else {
                g.setColor(Color.WHITE); g.fillRect(0, 0, w, h);
                if (grid) { g.setColor(new Color(47, 91, 234, 30)); for (int x = 0; x < w; x += 24) g.drawLine(x, 0, x, h); for (int y = 0; y < h; y += 24) g.drawLine(0, y, w, y); }
            }
            g.drawImage(layer, 0, 0, null);
            g.setColor(LINE); g.drawRect(0, 0, w - 1, h - 1); g.dispose();
        }
    }

    static JComponent swatch(Color c, Consumer<Color> pick) {
        JComponent s = new JComponent() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create(); g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(c); g2.fillOval(2, 2, 18, 18); g2.setColor(LINE); g2.drawOval(2, 2, 18, 18); g2.dispose();
            }
        };
        s.setPreferredSize(new Dimension(22, 22)); s.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        s.addMouseListener(new MouseAdapter() { public void mouseClicked(MouseEvent e) { pick.accept(c); } });
        return s;
    }

    static JPanel toolbar(Sketch s) {
        JPanel tb = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2)); tb.setOpaque(false); tb.setAlignmentX(Component.LEFT_ALIGNMENT);
        ButtonGroup g = new ButtonGroup();
        JToggleButton pen = new JToggleButton("Үзэг", true), hl = new JToggleButton("Тодруулагч"), er = new JToggleButton("Баллуур");
        for (JToggleButton t : new JToggleButton[]{pen, hl, er}) { g.add(t); t.setFocusPainted(false); tb.add(t); }
        pen.addActionListener(e -> { s.tool = "pen"; if (s.color.equals(YEL)) s.color = s.defColor; });
        hl.addActionListener(e -> { s.tool = "hl"; if (s.color.equals(s.defColor)) s.color = YEL; });
        er.addActionListener(e -> s.tool = "eraser");
        for (Color c : new Color[]{INK, new Color(0x2F5BEA), RED, new Color(0x1F8A57), new Color(0xF08A24), YEL}) tb.add(swatch(c, x -> s.color = x));
        JSlider sl = new JSlider(1, 12, s.size); sl.setPreferredSize(new Dimension(100, 24)); sl.setOpaque(false);
        sl.addChangeListener(e -> s.size = sl.getValue());
        tb.add(new JLabel("Зузаан")); tb.add(sl);
        tb.add(button("Буцаах", s::undoLast)); tb.add(button("Цэвэрлэх", s::clearAll));
        return tb;
    }

    static JComponent drawView(Block b) {
        JPanel p = new Wrap(new BorderLayout(0, 6)); p.setOpaque(false);
        Sketch s = new Sketch(null, 1000, 560, b.overlay, true, INK, true);
        s.onChange = () -> { b.overlay = png(s.layer); markDirty(); };
        p.add(toolbar(s), BorderLayout.NORTH);
        JPanel holder = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)); holder.setOpaque(false); holder.add(s);
        p.add(holder, BorderLayout.CENTER);
        return p;
    }

    static void setBase(Block b, BufferedImage im) { b.base = jpg(fit(im)); b.overlay = null; markDirty(); }

    static JComponent imageView(Block b, Runnable rebuild) {
        if (b.base == null) {
            JPanel z = new Wrap(new FlowLayout(FlowLayout.LEFT, 8, 8));
            z.setBorder(new LineBorder(LINE)); z.setOpaque(false);
            z.add(new JLabel("Зураг оруулах:"));
            z.add(button("Файл сонгох…", () -> {
                JFileChooser fc = new JFileChooser();
                fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Зураг (png, jpg, gif, bmp)", "png", "jpg", "jpeg", "gif", "bmp"));
                if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                    try {
                        BufferedImage im = ImageIO.read(fc.getSelectedFile());
                        if (im == null) throw new IOException("unsupported");
                        setBase(b, im); rebuild.run();
                    } catch (IOException ex) { JOptionPane.showMessageDialog(frame, "Зургийг уншиж чадсангүй.", "Алдаа", JOptionPane.ERROR_MESSAGE); }
                }
            }));
            z.add(button("Clipboard-аас буулгах", () -> {
                BufferedImage im = clipboardImage();
                if (im == null) JOptionPane.showMessageDialog(frame, "Clipboard дээр зураг алга.");
                else { setBase(b, im); rebuild.run(); }
            }));
            return z;
        }
        BufferedImage bi = img(b.base);
        JPanel p = new Wrap(new BorderLayout(0, 6)); p.setOpaque(false);
        Sketch s = new Sketch(bi, bi.getWidth(), bi.getHeight(), b.overlay, false, RED, false);
        s.onChange = () -> { b.overlay = png(s.layer); markDirty(); };
        JPanel tb = toolbar(s); tb.setVisible(false);
        JToggleButton tg = new JToggleButton("Зураг дээр зурах"); tg.setFocusPainted(false);
        tg.addActionListener(e -> { s.setDrawing(tg.isSelected()); tb.setVisible(tg.isSelected()); tg.setText(tg.isSelected() ? "Зурж дуусгах" : "Зураг дээр зурах"); p.revalidate(); });
        JPanel north = new JPanel(new BorderLayout()); north.setOpaque(false);
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)); row.setOpaque(false); row.add(tg);
        north.add(row, BorderLayout.NORTH); north.add(tb, BorderLayout.CENTER);
        p.add(north, BorderLayout.NORTH);
        JPanel holder = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)); holder.setOpaque(false); holder.add(s);
        JPanel mid = new JPanel(new BorderLayout(0, 6)); mid.setOpaque(false);
        mid.add(holder, BorderLayout.NORTH);
        JTextArea cap = new JTextArea(b.caption, 2, 40);
        cap.setLineWrap(true); cap.setWrapStyleWord(true); cap.setBackground(new Color(0xFFF8DC));
        cap.setBorder(new CompoundBorder(new LineBorder(LINE), new EmptyBorder(6, 8, 6, 8)));
        cap.getDocument().addDocumentListener(dl(() -> { b.caption = cap.getText(); markDirty(); cap.revalidate(); }));
        JLabel cl = new JLabel("Зургийн тайлбар:"); cl.setForeground(MUTED);
        JPanel capP = new JPanel(new BorderLayout(0, 2)); capP.setOpaque(false); capP.add(cl, BorderLayout.NORTH); capP.add(cap, BorderLayout.CENTER);
        mid.add(capP, BorderLayout.CENTER);
        p.add(mid, BorderLayout.CENTER);
        return p;
    }

    /* ===================== TOOLS ===================== */
    static class ToolsPanel extends JPanel {
        final Course c; final JPanel grid = new JPanel(new GridLayout(0, 2, 12, 12));
        ToolsPanel(Course c) {
            super(new BorderLayout(0, 10)); this.c = c; setBorder(new EmptyBorder(12, 4, 4, 4));
            JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            top.add(new JLabel("Хэрэгсэл нэмэх:"));
            for (Map.Entry<String, String> e : TOOLN.entrySet())
                top.add(button("+ " + e.getValue(), () -> { c.tools.add(new Tool(e.getKey())); markDirty(); rebuild(); }));
            add(top, BorderLayout.NORTH);
            grid.setOpaque(false);
            JPanel wrap = new JPanel(new BorderLayout()); wrap.setOpaque(false); wrap.add(grid, BorderLayout.NORTH);
            JScrollPane sp = new JScrollPane(wrap); sp.setBorder(null); sp.getViewport().setOpaque(false); sp.setOpaque(false);
            sp.getVerticalScrollBar().setUnitIncrement(18);
            add(sp, BorderLayout.CENTER);
            rebuild();
        }
        void rebuild() {
            grid.removeAll();
            if (c.tools.isEmpty()) grid.add(new JLabel("<html><span style='color:#5B6987'>Энэ хичээлд туслах хэрэгсэл хараахан алга. Дээрээс сонгоно уу.</span></html>"));
            for (Tool t : c.tools) grid.add(card(t));
            grid.revalidate(); grid.repaint();
        }
        JComponent card(Tool t) {
            JPanel p = new JPanel(new BorderLayout(0, 8)); p.setBackground(Color.WHITE);
            p.setBorder(new CompoundBorder(new LineBorder(LINE, 1, true), new EmptyBorder(10, 12, 12, 12)));
            JLabel title = new JLabel(TOOLN.get(t.type)); title.setFont(title.getFont().deriveFont(Font.BOLD, 15f)); title.setForeground(col(c.color));
            JButton del = button("✕", () -> {
                if (JOptionPane.showConfirmDialog(frame, "Энэ хэрэгслийг устгах уу?", "Устгах", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                    c.tools.remove(t); TIMERS.remove(t); markDirty(); rebuild();
                }
            });
            del.setMargin(new Insets(0, 6, 0, 6));
            JPanel h = new JPanel(new BorderLayout()); h.setOpaque(false); h.add(title, BorderLayout.WEST); h.add(del, BorderLayout.EAST);
            p.add(h, BorderLayout.NORTH);
            switch (t.type) {
                case "calc": p.add(calcBody(t), BorderLayout.CENTER); break;
                case "timer": p.add(timerBody(t), BorderLayout.CENTER); break;
                case "todo": p.add(todoBody(t, col(c.color)), BorderLayout.CENTER); break;
                default: p.add(formulaBody(t), BorderLayout.CENTER);
            }
            return p;
        }
    }

    /* ----- Calculator ----- */
    static class Calc {
        String s; int p;
        static double eval(String in) {
            Calc c = new Calc();
            c.s = in.replace("×", "*").replace("÷", "/").replace("−", "-").replace(",", ".").replace(" ", "");
            double v = c.expr();
            if (c.p < c.s.length() || Double.isNaN(v) || Double.isInfinite(v)) throw new IllegalArgumentException();
            return v;
        }
        boolean at(char ch) { return p < s.length() && s.charAt(p) == ch; }
        double expr() {
            double v = term();
            while (at('+') || at('-')) { char o = s.charAt(p++); double r = term(); v = o == '+' ? v + r : v - r; }
            return v;
        }
        double term() {
            double v = unary();
            while (at('*') || at('/')) { char o = s.charAt(p++); double r = unary(); v = o == '*' ? v * r : v / r; }
            return v;
        }
        double unary() {
            if (at('-')) { p++; return -unary(); }
            if (at('+')) { p++; return unary(); }
            return pow();
        }
        double pow() {
            double b = primary();
            if (at('^')) { p++; return Math.pow(b, unary()); }
            return b;
        }
        double primary() {
            if (p >= s.length()) throw new IllegalArgumentException();
            if (at('(')) { p++; double v = expr(); if (!at(')')) throw new IllegalArgumentException(); p++; return v; }
            if (at('√')) { p++; return Math.sqrt(primary()); }
            if (at('π')) { p++; return Math.PI; }
            int st = p;
            while (p < s.length() && (Character.isDigit(s.charAt(p)) || s.charAt(p) == '.')) p++;
            if (st == p) throw new IllegalArgumentException();
            return Double.parseDouble(s.substring(st, p));
        }
    }
    static String num(double r) {
        if (r == Math.rint(r) && Math.abs(r) < 1e15) return String.valueOf((long) r);
        return new java.math.BigDecimal(r).round(new java.math.MathContext(12)).stripTrailingZeros().toPlainString();
    }
    static JComponent calcBody(Tool t) {
        JTextField disp = new JTextField(); disp.setHorizontalAlignment(SwingConstants.RIGHT); disp.setFont(disp.getFont().deriveFont(Font.BOLD, 22f));
        JLabel res = new JLabel(" ", SwingConstants.RIGHT); res.setForeground(MUTED);
        JTextArea hist = new JTextArea(String.join("\n", t.hist.subList(Math.max(0, t.hist.size() - 4), t.hist.size()))); hist.setEditable(false); hist.setOpaque(false); hist.setForeground(MUTED); hist.setFont(hist.getFont().deriveFont(12f));
        Runnable preview = () -> { try { res.setText(disp.getText().isBlank() ? " " : "= " + num(Calc.eval(disp.getText()))); } catch (Exception e) { res.setText(" "); } };
        Runnable equals = () -> {
            try {
                String v = num(Calc.eval(disp.getText()));
                t.hist.add(disp.getText() + " = " + v); if (t.hist.size() > 20) t.hist.remove(0); markDirty();
                disp.setText(v); res.setText(" ");
                hist.setText(String.join("\n", t.hist.subList(Math.max(0, t.hist.size() - 4), t.hist.size())));
            } catch (Exception e) { res.setText("Илэрхийллийг шалгана уу"); }
        };
        disp.getDocument().addDocumentListener(dl(preview));
        disp.addActionListener(e -> equals.run());
        String[][] rows = {{"C", "(", ")", "⌫"}, {"7", "8", "9", "÷"}, {"4", "5", "6", "×"}, {"1", "2", "3", "−"}, {"0", ".", "^", "+"}, {"√", "π", "x²", "="}};
        JPanel keys = new JPanel(new GridLayout(0, 4, 4, 4)); keys.setOpaque(false);
        for (String[] row : rows) for (String k : row) {
            JButton b = new JButton(k); b.setFocusPainted(false); b.setFont(b.getFont().deriveFont(Font.BOLD, 15f));
            b.addActionListener(e -> {
                switch (k) {
                    case "C": disp.setText(""); break;
                    case "⌫": if (!disp.getText().isEmpty()) disp.setText(disp.getText().substring(0, disp.getText().length() - 1)); break;
                    case "=": equals.run(); break;
                    case "√": disp.setText(disp.getText() + "√("); break;
                    case "x²": disp.setText(disp.getText() + "^2"); break;
                    default: disp.setText(disp.getText() + k);
                }
                disp.requestFocusInWindow();
            });
            keys.add(b);
        }
        JPanel top = new JPanel(new BorderLayout(0, 2)); top.setOpaque(false); top.add(disp, BorderLayout.NORTH); top.add(res, BorderLayout.CENTER);
        JPanel p = new JPanel(new BorderLayout(0, 6)); p.setOpaque(false);
        p.add(top, BorderLayout.NORTH); p.add(keys, BorderLayout.CENTER); p.add(hist, BorderLayout.SOUTH);
        return p;
    }

    /* ----- Timer ----- */
    static class TS { long end; int left; boolean running; JLabel lbl; JButton go; }
    static TS ts(Tool t) { return TIMERS.computeIfAbsent(t, k -> { TS s = new TS(); s.left = t.minutes * 60; return s; }); }
    static String mmss(int s) { return String.format("%02d:%02d", s / 60, s % 60); }
    static JComponent timerBody(Tool t) {
        TS s = ts(t);
        JLabel big = new JLabel(mmss(s.left), SwingConstants.CENTER); big.setFont(big.getFont().deriveFont(Font.BOLD, 52f));
        s.lbl = big;
        JButton go = new JButton(s.running ? "Түр зогсоох" : "Эхлэх"); go.setFocusPainted(false); s.go = go;
        go.addActionListener(e -> {
            if (s.running) { s.left = Math.max(0, (int) Math.ceil((s.end - System.currentTimeMillis()) / 1000.0)); s.running = false; }
            else { if (s.left <= 0) s.left = t.minutes * 60; s.end = System.currentTimeMillis() + s.left * 1000L; s.running = true; }
            go.setText(s.running ? "Түр зогсоох" : "Эхлэх"); big.setText(mmss(s.left));
        });
        JButton reset = button("Дахин эхлүүлэх", () -> { s.running = false; s.left = t.minutes * 60; big.setText(mmss(s.left)); go.setText("Эхлэх"); });
        JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0)); row.setOpaque(false); row.add(go); row.add(reset);
        JPanel pre = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0)); pre.setOpaque(false);
        for (int m : new int[]{5, 15, 25, 45, 60}) {
            JButton b = button(m + " мин", () -> { t.minutes = m; markDirty(); if (!s.running) { s.left = m * 60; big.setText(mmss(s.left)); } });
            b.setMargin(new Insets(2, 6, 2, 6)); pre.add(b);
        }
        JPanel low = new JPanel(new GridLayout(0, 1, 0, 6)); low.setOpaque(false); low.add(row); low.add(pre);
        JPanel p = new JPanel(new BorderLayout(0, 8)); p.setOpaque(false);
        p.add(big, BorderLayout.CENTER); p.add(low, BorderLayout.SOUTH);
        return p;
    }

    /* ----- To-do ----- */
    static JComponent todoBody(Tool t, Color accent) {
        JPanel list = new JPanel(); list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS)); list.setOpaque(false);
        Runnable[] paint = new Runnable[1];
        paint[0] = () -> {
            list.removeAll();
            for (Item it : new ArrayList<>(t.items)) {
                JPanel row = new JPanel(new BorderLayout(4, 0)); row.setOpaque(false); row.setAlignmentX(Component.LEFT_ALIGNMENT);
                row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
                JCheckBox cb = new JCheckBox("<html>" + (it.done ? "<s style='color:gray'>" + esc(it.text) + "</s>" : esc(it.text)) + "</html>", it.done);
                cb.setOpaque(false); cb.setForeground(accent);
                cb.addActionListener(e -> { it.done = cb.isSelected(); markDirty(); paint[0].run(); });
                JButton x = button("✕", () -> { t.items.remove(it); markDirty(); paint[0].run(); });
                x.setMargin(new Insets(0, 5, 0, 5)); x.setBorderPainted(false); x.setContentAreaFilled(false);
                row.add(cb, BorderLayout.CENTER); row.add(x, BorderLayout.EAST); list.add(row);
            }
            list.revalidate(); list.repaint();
            Container c = SwingUtilities.getAncestorOfClass(JScrollPane.class, list); if (c != null) c.revalidate();
        };
        JTextField in = new JTextField();
        in.setToolTipText("Даалгавар бичээд Enter дарна уу");
        in.addActionListener(e -> {
            if (in.getText().isBlank()) return;
            Item it = new Item(); it.text = in.getText().trim(); t.items.add(it); in.setText(""); markDirty(); paint[0].run();
        });
        paint[0].run();
        JPanel p = new JPanel(new BorderLayout(0, 6)); p.setOpaque(false);
        JLabel hint = new JLabel("Даалгавар бичээд Enter дарна уу"); hint.setForeground(MUTED); hint.setFont(hint.getFont().deriveFont(12f));
        JPanel n = new JPanel(new BorderLayout(0, 2)); n.setOpaque(false); n.add(in, BorderLayout.NORTH); n.add(hint, BorderLayout.SOUTH);
        p.add(n, BorderLayout.NORTH); p.add(list, BorderLayout.CENTER);
        return p;
    }

    /* ----- Formulas ----- */
    static JComponent formulaBody(Tool t) {
        JPanel list = new JPanel(); list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS)); list.setOpaque(false);
        Runnable[] paint = new Runnable[1];
        paint[0] = () -> {
            list.removeAll();
            for (Item it : new ArrayList<>(t.items)) {
                JPanel box = new JPanel(new BorderLayout(0, 4)); box.setAlignmentX(Component.LEFT_ALIGNMENT);
                box.setBorder(new CompoundBorder(new LineBorder(LINE), new EmptyBorder(6, 8, 6, 8))); box.setBackground(new Color(0xF6F8FC));
                JTextField ti = new JTextField(it.title); ti.setFont(ti.getFont().deriveFont(Font.BOLD)); ti.setBorder(null); ti.setOpaque(false);
                ti.getDocument().addDocumentListener(dl(() -> { it.title = ti.getText(); markDirty(); }));
                JTextArea bo = new JTextArea(it.text, 2, 20); bo.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14)); bo.setLineWrap(true); bo.setOpaque(false);
                bo.getDocument().addDocumentListener(dl(() -> { it.text = bo.getText(); markDirty(); }));
                JButton x = button("Устгах", () -> { t.items.remove(it); markDirty(); paint[0].run(); });
                x.setMargin(new Insets(0, 6, 0, 6));
                JPanel r = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0)); r.setOpaque(false); r.add(x);
                box.add(ti, BorderLayout.NORTH); box.add(bo, BorderLayout.CENTER); box.add(r, BorderLayout.SOUTH);
                list.add(box); list.add(Box.createVerticalStrut(6));
            }
            list.revalidate(); list.repaint();
            Container c = SwingUtilities.getAncestorOfClass(JScrollPane.class, list); if (c != null) c.revalidate();
        };
        paint[0].run();
        JPanel p = new JPanel(new BorderLayout(0, 6)); p.setOpaque(false);
        p.add(button("+ Томьёо нэмэх", () -> { t.items.add(new Item()); markDirty(); paint[0].run(); }), BorderLayout.NORTH);
        p.add(list, BorderLayout.CENTER);
        return p;
    }

    /* ===================== REMINDERS ===================== */
    static void openReminders() {
        if (remDlg != null && remDlg.isShowing()) { remDlg.toFront(); return; }
        remDlg = new JDialog(frame, "Сануулга", false);
        JPanel root = new JPanel(new BorderLayout(0, 12)); root.setBorder(new EmptyBorder(16, 18, 16, 18));
        JLabel st = new JLabel("<html><div style='width:420px;color:#5B6987'>" + (tray != null
                ? "Цонхыг хаахад Дэвтэр ар талд (tray) ажиллаж, сануулгыг компьютерийн мэдэгдлээр харуулна."
                : "Энэ системд tray дэмжигдэхгүй тул сануулга ирэхийн тулд Дэвтэр ажиллаж байх ёстой.") + "</div></html>");
        JPanel form = new JPanel(new GridLayout(0, 2, 8, 8));
        JTextField title = new JTextField();
        JComboBox<Object> cc = new JComboBox<>(); cc.addItem("Хичээл сонгоогүй"); for (Course c : data.courses) cc.addItem(c);
        Course cur = byId(data.activeId); if (cur != null) cc.setSelectedItem(cur);
        cc.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                return super.getListCellRendererComponent(l, v instanceof Course ? ((Course) v).name : v, i, s, f);
            }
        });
        Calendar cal = Calendar.getInstance(); cal.add(Calendar.HOUR_OF_DAY, 1); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0);
        JSpinner when = new JSpinner(new SpinnerDateModel(cal.getTime(), null, null, Calendar.MINUTE));
        when.setEditor(new JSpinner.DateEditor(when, "yyyy-MM-dd HH:mm"));
        JComboBox<String> rep = new JComboBox<>(new String[]{"Нэг удаа", "Өдөр бүр", "7 хоног бүр"});
        form.add(new JLabel("Юу сануулах вэ?")); form.add(title);
        form.add(new JLabel("Хичээл")); form.add(cc);
        form.add(new JLabel("Хэзээ (он-сар-өдөр цаг:минут)")); form.add(when);
        form.add(new JLabel("Давтах")); form.add(rep);
        JButton add = button("+ Сануулга нэмэх", () -> {
            if (title.getText().isBlank()) { JOptionPane.showMessageDialog(remDlg, "Юу сануулахыг бичнэ үү."); return; }
            Reminder r = new Reminder(); r.title = title.getText().trim();
            r.courseId = cc.getSelectedItem() instanceof Course ? ((Course) cc.getSelectedItem()).id : null;
            r.at = LocalDateTime.ofInstant(((Date) when.getValue()).toInstant(), java.time.ZoneId.systemDefault());
            r.repeat = new String[]{"none", "daily", "weekly"}[rep.getSelectedIndex()];
            data.reminders.add(r); markDirty(); title.setText(""); updateBell(); refreshRem();
        });
        JButton test = button("Мэдэгдэл шалгах", () -> notifyUser("Туршилтын сануулга", "Мэдэгдэл ажиллаж байна."));
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0)); btns.add(add); btns.add(test);
        JPanel north = new JPanel(new BorderLayout(0, 10)); north.add(st, BorderLayout.NORTH); north.add(form, BorderLayout.CENTER); north.add(btns, BorderLayout.SOUTH);
        remList = new JPanel(); remList.setLayout(new BoxLayout(remList, BoxLayout.Y_AXIS));
        JScrollPane sp = new JScrollPane(remList); sp.setBorder(new TitledBorder(new LineBorder(LINE), "Миний сануулгууд"));
        sp.setPreferredSize(new Dimension(520, 220));
        root.add(north, BorderLayout.NORTH); root.add(sp, BorderLayout.CENTER);
        remDlg.setContentPane(root); remDlg.pack(); remDlg.setLocationRelativeTo(frame);
        refreshRem(); remDlg.setVisible(true);
    }
    static void refreshRem() {
        if (remList == null) return;
        remList.removeAll();
        List<Reminder> rs = new ArrayList<>(data.reminders);
        rs.sort(Comparator.comparing((Reminder r) -> r.done).thenComparing(r -> r.at));
        if (rs.isEmpty()) remList.add(new JLabel("  Сануулга хараахан алга."));
        for (Reminder r : rs) {
            Course c = byId(r.courseId);
            String meta = r.at.format(DT) + ("daily".equals(r.repeat) ? " · өдөр бүр" : "weekly".equals(r.repeat) ? " · 7 хоног бүр" : "") + (c != null ? " · " + c.name : "") + (r.done ? " · дууссан" : "");
            JPanel row = new JPanel(new BorderLayout(6, 0)); row.setBorder(new CompoundBorder(new MatteBorder(0, 0, 1, 0, LINE), new EmptyBorder(6, 6, 6, 6)));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
            JLabel l = new JLabel("<html><b>" + esc(r.title) + "</b><br><span style='color:#5B6987;font-size:11px'>" + esc(meta) + "</span></html>");
            if (r.done) l.setForeground(Color.GRAY);
            JButton x = button("Устгах", () -> { data.reminders.remove(r); markDirty(); updateBell(); refreshRem(); });
            row.add(l, BorderLayout.CENTER); row.add(x, BorderLayout.EAST); remList.add(row);
        }
        remList.revalidate(); remList.repaint();
    }

    /* ===================== TICK (reminders + timers) ===================== */
    static void tick() {
        LocalDateTime now = LocalDateTime.now(); boolean ch = false;
        for (Reminder r : new ArrayList<>(data.reminders)) {
            if (r.done || r.at.isAfter(now)) continue;
            Course c = byId(r.courseId);
            notifyUser(r.title, c == null ? "Сануулга" : c.name); ch = true;
            if ("none".equals(r.repeat)) r.done = true;
            else { LocalDateTime nx = r.at; while (!nx.isAfter(now)) nx = "daily".equals(r.repeat) ? nx.plusDays(1) : nx.plusWeeks(1); r.at = nx; }
        }
        if (ch) { markDirty(); updateBell(); if (remDlg != null && remDlg.isShowing()) refreshRem(); }
        long t = System.currentTimeMillis();
        for (TS s : TIMERS.values()) {
            if (!s.running) continue;
            s.left = Math.max(0, (int) Math.ceil((s.end - t) / 1000.0));
            if (s.lbl != null) s.lbl.setText(mmss(s.left));
            if (s.left == 0) { s.running = false; if (s.go != null) s.go.setText("Эхлэх"); notifyUser("Цаг дууслаа", "Цаг хэмжигч дууслаа."); }
        }
    }

    /* ===================== TRAY ===================== */
    static Image appIcon(int s) {
        BufferedImage b = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = b.createGraphics(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(col(PALETTE[0])); g.fillRoundRect(s / 8, 0, s * 3 / 4, s, s / 5, s / 5);
        g.setColor(Color.WHITE); g.fillRect(s * 3 / 10, s / 5, s * 2 / 5, s / 16); g.fillRect(s * 3 / 10, s * 2 / 5, s * 2 / 5, s / 16); g.fillRect(s * 3 / 10, s * 3 / 5, s * 2 / 8, s / 16);
        g.dispose(); return b;
    }
    static void setupTray() {
        try {
            if (!SystemTray.isSupported()) return;
            PopupMenu m = new PopupMenu();
            MenuItem open = new MenuItem("Дэвтэр нээх"); open.addActionListener(e -> { frame.setVisible(true); frame.setState(Frame.NORMAL); frame.toFront(); });
            MenuItem quit = new MenuItem("Гарах"); quit.addActionListener(e -> { saveNow(); System.exit(0); });
            m.add(open); m.addSeparator(); m.add(quit);
            tray = new TrayIcon(appIcon(32), "Дэвтэр", m); tray.setImageAutoSize(true);
            tray.addActionListener(e -> { frame.setVisible(true); frame.setState(Frame.NORMAL); frame.toFront(); });
            SystemTray.getSystemTray().add(tray);
        } catch (Exception e) { tray = null; }
    }
}
