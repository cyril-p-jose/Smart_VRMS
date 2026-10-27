package in.smartvrms.desktop.ui;

import in.smartvrms.contracts.*;
import in.smartvrms.desktop.integration.FleetHttpClient;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.math.*;
import java.time.LocalDate;
import java.time.Instant;
import java.util.*;
import java.util.List;

/** Real records only. All network work runs off the EDT; stale responses cannot repopulate after logout. */
public final class LiveFleetView extends JPanel {
    private final FleetHttpClient api;
    private final boolean administrator;
    private final JButton refresh = Design.button("Refresh fleet", false);
    private final JButton add = Design.button("Register vehicle", true);
    private final JButton category = Design.button("Add category", false);
    private final JButton details = Design.button("Vehicle details & history", false);
    private final JButton book = Design.button("Book this vehicle", false);
    private final JTextField search = new JTextField(12);
    private final JComboBox<String> categoryFilter = new JComboBox<>(new String[]{"All categories"});
    private final JLabel feedback = Design.label("Loading company fleet…", 12, Design.MUTED);

    private final DefaultTableModel rows = new DefaultTableModel(new String[]{"Registration", "Vehicle", "Category", "Seats", "Daily rate (INR)", "Status"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(rows);
    private final JTable table;

    private List<VehicleCategoryRecord> categories = List.of();
    private List<VehicleRecord> currentVehicles = List.of();
    private SwingWorker<?, ?> pending;
    private boolean active = true;
    private final java.util.function.Consumer<VehicleRecord> onShortlist;

    public LiveFleetView(FleetHttpClient api, StaffSession session) {
        this(api, session, null);
    }

    public LiveFleetView(FleetHttpClient api, StaffSession session, java.util.function.Consumer<VehicleRecord> onShortlist) {
        super(new BorderLayout(12, 12));
        this.api = api;
        this.administrator = session.isAdministrator();
        this.onShortlist = onShortlist;
        setOpaque(false);

        JPanel header = Design.card("Company fleet", "Records loaded from your authenticated company account.");
        JPanel actionRow = Design.row(refresh, add, category, details, book);

        search.putClientProperty("html.disable", Boolean.TRUE);
        search.getAccessibleContext().setAccessibleName("Filter vehicles by plate, make or model");
        categoryFilter.putClientProperty("html.disable", Boolean.TRUE);
        categoryFilter.getAccessibleContext().setAccessibleName("Filter vehicles by category");

        JPanel filterRow = Design.row(new JLabel("Search:"), search, new JLabel("Category:"), categoryFilter);
        header.add(actionRow);
        header.add(Box.createVerticalStrut(8));
        header.add(filterRow);
        header.add(Box.createVerticalStrut(8));
        header.add(feedback);
        add(header, BorderLayout.NORTH);

        table = new JTable(rows);
        table.setRowSorter(sorter);
        table.setRowHeight(32);
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        var plainText = new javax.swing.table.DefaultTableCellRenderer();
        plainText.putClientProperty("html.disable", Boolean.TRUE);
        table.setDefaultRenderer(Object.class, plainText);
        table.getAccessibleContext().setAccessibleName("Live company vehicle records");
        table.getTableHeader().setFont(new Font(Font.DIALOG, Font.BOLD, 12));
        table.getTableHeader().setPreferredSize(new Dimension(0, 34));

        JScrollPane scroller = new JScrollPane(table);
        scroller.setColumnHeaderView(table.getTableHeader());
        add(scroller);

        refresh.addActionListener(event -> reload());
        category.addActionListener(event -> categoryDialog());
        add.addActionListener(event -> vehicleDialog());
        details.addActionListener(event -> showSelectedVehicleDetails());
        book.addActionListener(event -> {
            int selected = table.getSelectedRow();
            if (selected >= 0 && onShortlist != null) {
                int modelIdx = table.convertRowIndexToModel(selected);
                if (modelIdx >= 0 && modelIdx < currentVehicles.size()) {
                    onShortlist.accept(currentVehicles.get(modelIdx));
                }
            }
        });

        table.getSelectionModel().addListSelectionListener(event -> {
            boolean hasSel = !table.getSelectionModel().isSelectionEmpty();
            details.setEnabled(hasSel);
            book.setEnabled(hasSel && onShortlist != null);
        });

        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { applyFilters(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { applyFilters(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { applyFilters(); }
        });

        categoryFilter.addActionListener(event -> applyFilters());

        reload();
    }

    public void close() {
        active = false;
        if (pending != null) pending.cancel(true);
        rows.setRowCount(0);
        categories = List.of();
        currentVehicles = List.of();
    }

    private void busy(boolean value) {
        refresh.setEnabled(!value);
        add.setEnabled(!value && administrator && !categories.isEmpty());
        category.setEnabled(!value && administrator);
        details.setEnabled(!value && table.getSelectedRow() >= 0);
        book.setEnabled(!value && table.getSelectedRow() >= 0 && onShortlist != null);
    }

    private void applyFilters() {
        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        String selectedCat = (String) categoryFilter.getSelectedItem();
        boolean allCats = selectedCat == null || "All categories".equals(selectedCat);

        sorter.setRowFilter(new RowFilter<DefaultTableModel, Integer>() {
            @Override
            public boolean include(Entry<? extends DefaultTableModel, ? extends Integer> entry) {
                String plate = entry.getStringValue(0).toLowerCase(Locale.ROOT);
                String name = entry.getStringValue(1).toLowerCase(Locale.ROOT);
                String cat = entry.getStringValue(2);
                String status = entry.getStringValue(5).toLowerCase(Locale.ROOT);

                boolean matchesQuery = query.isBlank()
                        || plate.contains(query)
                        || name.contains(query)
                        || cat.toLowerCase(Locale.ROOT).contains(query)
                        || status.contains(query);

                boolean matchesCategory = allCats || cat.equalsIgnoreCase(selectedCat);
                return matchesQuery && matchesCategory;
            }
        });
    }

    private record FleetData(List<VehicleCategoryRecord> categories, List<VehicleRecord> vehicles) {}

    public void reload() {
        if (!active) return;
        busy(true);
        feedback.setText("Loading company records…");
        pending = new SwingWorker<FleetData, Void>() {
            protected FleetData doInBackground() {
                return new FleetData(api.categories(), api.vehicles());
            }
            protected void done() {
                if (!active) return;
                try {
                    var result = get();
                    categories = result.categories();
                    currentVehicles = result.vehicles();
                    rows.setRowCount(0);

                    // Rebuild category filter dropdown
                    String currentSelection = (String) categoryFilter.getSelectedItem();
                    categoryFilter.removeAllItems();
                    categoryFilter.addItem("All categories");
                    for (var cat : categories) {
                        categoryFilter.addItem(cat.name());
                    }
                    if (currentSelection != null) categoryFilter.setSelectedItem(currentSelection);

                    for (var item : result.vehicles()) {
                        rows.addRow(new Object[]{
                            item.registrationPlate(),
                            item.manufacturer() + " " + item.model(),
                            item.categoryName(),
                            item.seatingCapacity(),
                            BigDecimal.valueOf(item.dailyRatePaise(), 2).toPlainString(),
                            item.operationalStatus()
                        });
                    }
                    feedback.setText(result.vehicles().isEmpty()
                            ? "No vehicles registered yet. Administrators can add a category and register the first vehicle."
                            : result.vehicles().size() + " vehicle records loaded.");
                } catch (Exception ex) {
                    rows.setRowCount(0);
                    categories = List.of();
                    currentVehicles = List.of();
                    failure(ex);
                } finally {
                    busy(false);
                }
            }
        };
        pending.execute();
    }

    private void categoryDialog() {
        JTextField code = new JTextField(), name = new JTextField();
        JPanel form = new JPanel(new GridLayout(0, 2, 10, 10));
        form.add(new JLabel("Code (e.g. SUV, BIKE)"));
        form.add(code);
        form.add(new JLabel("Display name"));
        form.add(name);
        if (JOptionPane.showConfirmDialog(this, form, "Add vehicle category", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        String key = code.getText().strip().toUpperCase(Locale.ROOT), label = name.getText().strip();
        if (!key.matches("[A-Z][A-Z0-9_]{1,19}") || label.isBlank() || label.length() > 50) {
            feedback.setText("Enter a 2–20 character category code and a name up to 50 characters.");
            return;
        }
        save(() -> api.createCategory(key, label), "Category saved.");
    }

    private void vehicleDialog() {
        JComboBox<VehicleCategoryRecord> categoryList = new JComboBox<>(categories.toArray(VehicleCategoryRecord[]::new));
        var plainCategory = new DefaultListCellRenderer();
        plainCategory.putClientProperty("html.disable", Boolean.TRUE);
        categoryList.setRenderer(plainCategory);
        JTextField plate = new JTextField(), make = new JTextField(), model = new JTextField(), seats = new JTextField("5"), rate = new JTextField();
        JPanel form = new JPanel(new GridLayout(0, 2, 10, 10));
        for (JComponent[] pair : new JComponent[][]{
                {new JLabel("Category"), categoryList},
                {new JLabel("Registration plate"), plate},
                {new JLabel("Manufacturer"), make},
                {new JLabel("Model"), model},
                {new JLabel("Seats"), seats},
                {new JLabel("Daily rate (INR)"), rate}}) {
            form.add(pair[0]);
            form.add(pair[1]);
        }
        if (JOptionPane.showConfirmDialog(this, form, "Register company vehicle", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        try {
            var selected = (VehicleCategoryRecord) categoryList.getSelectedItem();
            int capacity = Integer.parseInt(seats.getText().strip());
            long daily = new BigDecimal(rate.getText().strip()).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
            String registration = plate.getText().strip().toUpperCase(Locale.ROOT), manufacturer = make.getText().strip(), vehicleModel = model.getText().strip();
            if (selected == null || capacity <= 0 || daily <= 0 || registration.length() < 4 || registration.length() > 20 || manufacturer.isBlank() || manufacturer.length() > 80 || vehicleModel.isBlank() || vehicleModel.length() > 80)
                throw new IllegalArgumentException();
            long hourly = BigDecimal.valueOf(daily).divide(BigDecimal.valueOf(24), 0, RoundingMode.HALF_UP).longValueExact();
            save(() -> api.registerVehicle(selected.id(), registration, manufacturer, vehicleModel, capacity, daily, hourly), "Vehicle saved.");
        } catch (RuntimeException ex) {
            feedback.setText("Check the registration, manufacturer, model, positive seat count and rate with at most two decimal places.");
        }
    }

    private void showSelectedVehicleDetails() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) return;
        int modelRow = table.convertRowIndexToModel(viewRow);
        if (modelRow >= currentVehicles.size()) return;
        VehicleRecord vehicle = currentVehicles.get(modelRow);

        JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "Vehicle Details: " + vehicle.registrationPlate(), true);
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.setSize(750, 550);
        dialog.setLocationRelativeTo(this);

        // Header info
        JPanel infoPanel = Design.card("Vehicle Particulars",
                vehicle.manufacturer() + " " + vehicle.model() + " (" + vehicle.categoryName() + ") | " +
                vehicle.seatingCapacity() + " seats | INR " + BigDecimal.valueOf(vehicle.dailyRatePaise(), 2).toPlainString() + "/day | Status: " + vehicle.operationalStatus());

        JTabbedPane tabs = new JTabbedPane();

        // Restrictions Tab
        DefaultTableModel restModel = new DefaultTableModel(new String[]{"ID", "Type", "Reason", "Start", "End", "Active"}, 0);
        JTable restTable = new JTable(restModel);
        restTable.setRowHeight(24);
        JPanel restPanel = new JPanel(new BorderLayout(8, 8));
        restPanel.add(new JScrollPane(restTable), BorderLayout.CENTER);

        JButton freezeBtn = Design.button("Freeze asset", false);
        JButton resolveBtn = Design.button("Resolve selected restriction", false);
        JPanel restActions = Design.row(freezeBtn, resolveBtn);
        restPanel.add(restActions, BorderLayout.SOUTH);
        tabs.addTab("Asset Restrictions", restPanel);

        // Documents Tab
        DefaultTableModel docModel = new DefaultTableModel(new String[]{"Type", "Version", "Issue Date", "Expiry Date", "File ID"}, 0);
        JTable docTable = new JTable(docModel);
        docTable.setRowHeight(24);
        JPanel docPanel = new JPanel(new BorderLayout(8, 8));
        docPanel.add(new JScrollPane(docTable), BorderLayout.CENTER);

        JButton addDocBtn = Design.button("Add document renewal", false);
        JButton downloadDocBtn = Design.button("Download / Signed link", false);
        JPanel docActions = Design.row(addDocBtn, downloadDocBtn);
        docPanel.add(docActions, BorderLayout.SOUTH);
        tabs.addTab("Vehicle Documents", docPanel);

        dialog.add(infoPanel, BorderLayout.NORTH);
        dialog.add(tabs, BorderLayout.CENTER);

        // Loader for details
        Runnable loadDetails = () -> {
            new SwingWorker<Void, Void>() {
                List<AssetRestrictionRecord> rests;
                List<VehicleDocumentRecord> docs;
                protected Void doInBackground() {
                    rests = api.restrictions("VEHICLE", vehicle.id());
                    docs = api.documents(vehicle.id());
                    return null;
                }
                protected void done() {
                    try {
                        get();
                        restModel.setRowCount(0);
                        if (rests != null) {
                            for (var r : rests) {
                                restModel.addRow(new Object[]{r.id().toString(), r.restrictionType(), r.reason(), r.startTime(), r.endTime(), r.active() ? "ACTIVE" : "RESOLVED"});
                            }
                        }
                        docModel.setRowCount(0);
                        if (docs != null) {
                            for (var d : docs) {
                                docModel.addRow(new Object[]{d.docType(), d.version(), d.issueDate(), d.expiryDate() != null ? d.expiryDate().toString() : "NON-EXPIRING", d.fileId().toString()});
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }.execute();
        };
        loadDetails.run();

        // Freeze action
        freezeBtn.addActionListener(ev -> {
            JTextField reasonField = new JTextField();
            JPanel fPanel = new JPanel(new GridLayout(0, 2, 8, 8));
            fPanel.add(new JLabel("Reason:"));
            fPanel.add(reasonField);
            if (JOptionPane.showConfirmDialog(dialog, fPanel, "Freeze Asset", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                String reason = reasonField.getText().strip();
                if (!reason.isBlank()) {
                    new SwingWorker<Void, Void>() {
                        protected Void doInBackground() {
                            api.freezeAsset("VEHICLE", vehicle.id(), reason, Instant.now(), null);
                            return null;
                        }
                        protected void done() {
                            loadDetails.run();
                            reload();
                        }
                    }.execute();
                }
            }
        });

        // Resolve action
        resolveBtn.addActionListener(ev -> {
            int sel = restTable.getSelectedRow();
            if (sel >= 0) {
                UUID restId = UUID.fromString((String) restModel.getValueAt(sel, 0));
                new SwingWorker<Void, Void>() {
                    protected Void doInBackground() {
                        api.resolveRestriction(restId);
                        return null;
                    }
                    protected void done() {
                        loadDetails.run();
                        reload();
                    }
                }.execute();
            }
        });

        // Add document renewal action
        addDocBtn.addActionListener(ev -> {
            JComboBox<String> docTypes = new JComboBox<>(new String[]{"RC", "INSURANCE", "PUC", "PERMIT", "FITNESS", "TAX"});
            JTextField issueField = new JTextField(LocalDate.now().toString());
            JTextField expiryField = new JTextField(LocalDate.now().plusYears(1).toString());
            JTextField fileIdField = new JTextField(UUID.randomUUID().toString());
            JPanel dPanel = new JPanel(new GridLayout(0, 2, 8, 8));
            dPanel.add(new JLabel("Document Type:"));
            dPanel.add(docTypes);
            dPanel.add(new JLabel("Issue Date (YYYY-MM-DD):"));
            dPanel.add(issueField);
            dPanel.add(new JLabel("Expiry Date (optional):"));
            dPanel.add(expiryField);
            dPanel.add(new JLabel("File ID (from Storage):"));
            dPanel.add(fileIdField);
            if (JOptionPane.showConfirmDialog(dialog, dPanel, "Add Vehicle Document", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                try {
                    String type = (String) docTypes.getSelectedItem();
                    LocalDate issue = LocalDate.parse(issueField.getText().strip());
                    String expText = expiryField.getText().strip();
                    LocalDate expiry = expText.isBlank() ? null : LocalDate.parse(expText);
                    UUID fileId = UUID.fromString(fileIdField.getText().strip());
                    new SwingWorker<Void, Void>() {
                        protected Void doInBackground() {
                            api.addDocument(vehicle.id(), type, fileId, issue, expiry);
                            return null;
                        }
                        protected void done() {
                            loadDetails.run();
                        }
                    }.execute();
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(dialog, "Invalid document metadata: " + ex.getMessage());
                }
            }
        });

        // Download document link
        downloadDocBtn.addActionListener(ev -> {
            int sel = docTable.getSelectedRow();
            if (sel >= 0) {
                UUID fileId = UUID.fromString((String) docModel.getValueAt(sel, 4));
                new SwingWorker<String, Void>() {
                    protected String doInBackground() {
                        return api.downloadUrl(fileId);
                    }
                    protected void done() {
                        try {
                            String url = get();
                            JOptionPane.showMessageDialog(dialog, "Signed Download URL (short-lived):\n" + url);
                        } catch (Exception ex) {
                            JOptionPane.showMessageDialog(dialog, "File download is unavailable. Private Storage signing is not configured on the server.");
                        }
                    }
                }.execute();
            }
        });

        dialog.setVisible(true);
    }

    private void save(Runnable operation, String success) {
        if (!active) return;
        busy(true);
        feedback.setText("Saving…");
        pending = new SwingWorker<Void, Void>() {
            protected Void doInBackground() {
                operation.run();
                return null;
            }
            protected void done() {
                if (!active) return;
                try {
                    get();
                    feedback.setText(success);
                    reload();
                } catch (Exception ex) {
                    failure(ex);
                    busy(false);
                }
            }
        };
        pending.execute();
    }

    private void failure(Exception failure) {
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        feedback.setText(cause instanceof SecurityException
                ? "Access is no longer authorized. Sign out and sign in again."
                : "Request did not complete. Refresh records before retrying; no successful save is assumed.");
    }
}
