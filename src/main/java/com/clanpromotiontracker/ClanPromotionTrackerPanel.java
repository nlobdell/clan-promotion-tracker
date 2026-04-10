package com.clanpromotiontracker;

import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Insets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

final class ClanPromotionTrackerPanel extends PluginPanel
{
	private static final int PANEL_TEXT_COLUMNS = 22;
	private static final int INFO_AREA_ROWS = 3;
	private static final DateTimeFormatter SYNC_TIMESTAMP_FORMAT =
		DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.ENGLISH);

	enum LocalFilter
	{
		ALL,
		READY,
		NEEDS_ATTENTION,
		MISSING
	}

	private final JPanel content = new JPanel();
	private final JTextArea infoArea = new JTextArea();
	private final JButton refreshButton = new JButton("Refresh");
	private final JButton stopButton = new JButton("Stop");
	private final JButton copyButton = new JButton("Copy CSV");
	private final JButton saveButton = new JButton("Save CSV");
	private final JButton copyReportButton = new JButton("Copy Report");
	private final JButton saveReportButton = new JButton("Save Report");
	private final JButton exportCacheButton = new JButton("Export Cache");
	private final JButton importCacheButton = new JButton("Import Cache");
	private final JLabel womSyncLabel = new JLabel("WOM group: not configured");
	private final JTextField searchField = new JTextField();
	private final JToggleButton allFilterButton = new JToggleButton("All");
	private final JToggleButton readyFilterButton = new JToggleButton("Ready");
	private final JToggleButton needsAttentionFilterButton = new JToggleButton("Needs attention");
	private final JToggleButton missingFilterButton = new JToggleButton("Missing");

	private final Consumer<String> ignoreUserConsumer;
	private final Consumer<PromotionRecord> openWomProfileConsumer;

	private List<PromotionRecord> sourceRecords = Collections.emptyList();
	private int currentMaxDisplayed;
	private LocalFilter activeFilter = LocalFilter.ALL;
	private int womGroupId;
	private long womLastSuccessfulSyncMillis;
	private long hydrationCooldownUntilMillis;
	private String hydrationCooldownReason;

	ClanPromotionTrackerPanel(
		Runnable refreshAction,
		Runnable stopAction,
		Runnable copyAction,
		Runnable saveAction,
		Runnable copyReportAction,
		Runnable saveReportAction,
		Runnable exportCacheAction,
		Runnable importCacheAction,
		Consumer<String> ignoreUserConsumer,
		Consumer<PromotionRecord> openWomProfileConsumer)
	{
		this.ignoreUserConsumer = ignoreUserConsumer;
		this.openWomProfileConsumer = openWomProfileConsumer;

		setLayout(new BorderLayout(0, 8));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(new EmptyBorder(8, 8, 8, 8));

		JPanel headerPanel = new JPanel();
		headerPanel.setOpaque(false);
		headerPanel.setLayout(new BoxLayout(headerPanel, BoxLayout.Y_AXIS));

		JLabel titleLabel = new JLabel("Clan Promotion Tracker");
		titleLabel.setForeground(Color.WHITE);
		titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 14f));
		titleLabel.setAlignmentX(LEFT_ALIGNMENT);

		JPanel legendRow = buildLegendRow();

		JPanel buttonRow = new JPanel();
		buttonRow.setOpaque(false);
		buttonRow.setLayout(new GridLayout(1, 4, 4, 0));
		buttonRow.setAlignmentX(LEFT_ALIGNMENT);
		buttonRow.add(configureButton(refreshButton, refreshAction));
		buttonRow.add(configureButton(stopButton, stopAction));
		buttonRow.add(configureButton(copyButton, copyAction));
		buttonRow.add(configureButton(saveButton, saveAction));

		JPanel reportButtonRow = new JPanel();
		reportButtonRow.setOpaque(false);
		reportButtonRow.setLayout(new GridLayout(1, 2, 4, 0));
		reportButtonRow.setAlignmentX(LEFT_ALIGNMENT);
		reportButtonRow.add(configureButton(copyReportButton, copyReportAction));
		reportButtonRow.add(configureButton(saveReportButton, saveReportAction));

		JPanel cacheButtonRow = new JPanel();
		cacheButtonRow.setOpaque(false);
		cacheButtonRow.setLayout(new GridLayout(1, 2, 4, 0));
		cacheButtonRow.setAlignmentX(LEFT_ALIGNMENT);
		cacheButtonRow.add(configureButton(exportCacheButton, exportCacheAction));
		cacheButtonRow.add(configureButton(importCacheButton, importCacheAction));

		JPanel filterRow = buildFilterRow();
		JPanel searchRow = buildSearchRow();
		configureWomSyncLabel();
		configureInfoArea();

		headerPanel.add(titleLabel);
		headerPanel.add(Box.createVerticalStrut(4));
		headerPanel.add(legendRow);
		headerPanel.add(Box.createVerticalStrut(6));
		headerPanel.add(buttonRow);
		headerPanel.add(Box.createVerticalStrut(4));
		headerPanel.add(reportButtonRow);
		headerPanel.add(Box.createVerticalStrut(4));
		headerPanel.add(cacheButtonRow);
		headerPanel.add(Box.createVerticalStrut(6));
		headerPanel.add(filterRow);
		headerPanel.add(Box.createVerticalStrut(4));
		headerPanel.add(searchRow);
		headerPanel.add(Box.createVerticalStrut(4));
		headerPanel.add(womSyncLabel);
		headerPanel.add(infoArea);
		add(headerPanel, BorderLayout.NORTH);

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		content.setOpaque(false);
		add(content, BorderLayout.CENTER);

		currentMaxDisplayed = 0;
		updateFilterButtonStyles();
	}

	void setBusy(boolean busy)
	{
		SwingUtilities.invokeLater(() ->
		{
			refreshButton.setEnabled(!busy);
			stopButton.setEnabled(true);
			copyButton.setEnabled(!busy);
			saveButton.setEnabled(!busy);
			copyReportButton.setEnabled(!busy);
			saveReportButton.setEnabled(!busy);
			exportCacheButton.setEnabled(!busy);
			importCacheButton.setEnabled(!busy);
		});
	}

	void setInfoText(String text)
	{
		SwingUtilities.invokeLater(() ->
		{
			infoArea.setText(text == null ? "" : text);
			infoArea.setToolTipText(text);
			infoArea.setVisible(text != null && !text.isBlank());
		});
	}

	void setRecords(List<PromotionRecord> records, int maxDisplayed)
	{
		SwingUtilities.invokeLater(() ->
		{
			sourceRecords = records == null ? Collections.emptyList() : new ArrayList<>(records);
			currentMaxDisplayed = maxDisplayed;
			renderRecords();
		});
	}

	void setWomContext(int groupId, long lastSuccessfulSyncMillis, long cooldownUntilMillis, String cooldownReason)
	{
		SwingUtilities.invokeLater(() ->
		{
			womGroupId = groupId;
			womLastSuccessfulSyncMillis = lastSuccessfulSyncMillis;
			hydrationCooldownUntilMillis = cooldownUntilMillis;
			hydrationCooldownReason = cooldownReason;
			updateWomSyncLabel();
			renderRecords();
		});
	}

	static boolean matchesLocalFilter(PromotionRecord record, LocalFilter filter)
	{
		if (record == null || filter == null)
		{
			return false;
		}

		switch (filter)
		{
			case READY:
				return record.getStatus() == PromotionStatus.READY
					|| record.getStatus() == PromotionStatus.APPROXIMATE_BASELINE;
			case NEEDS_ATTENTION:
				return record.getStatus() == PromotionStatus.XP_NOT_FETCHED
					|| record.getStatus() == PromotionStatus.NO_WOM_MATCH
					|| record.getStatus() == PromotionStatus.UNKNOWN_RANK;
			case MISSING:
				return record.getStatus() == PromotionStatus.XP_NOT_FETCHED
					|| record.getStatus() == PromotionStatus.NO_WOM_MATCH;
			case ALL:
			default:
				return true;
		}
	}

	static String buildReasonText(PromotionRecord record, int womGroupId, long cooldownUntilMillis, String cooldownReason, long nowMillis)
	{
		if (record == null)
		{
			return null;
		}

		switch (record.getStatus())
		{
			case NO_WOM_MATCH:
				return womGroupId > 0
					? "Not found in WOM group " + womGroupId + " snapshot."
					: "Not found in configured WOM group snapshot.";
			case XP_NOT_FETCHED:
				long remainingSeconds = Math.max(0L, TimeUnit.MILLISECONDS.toSeconds(cooldownUntilMillis - nowMillis));
				if (remainingSeconds > 0L)
				{
					String reason = cooldownReason == null || cooldownReason.isBlank() ? "WOM cooldown" : cooldownReason;
					return "XP data fetch deferred by " + reason + " (" + remainingSeconds + "s remaining).";
				}
				return "XP baseline/current data fetch is pending hydration.";
			case UNKNOWN_RANK:
				return "Current rank is not in configured ladder.";
			case NOT_READY:
				return "Does not yet meet month/XP requirements for next rank.";
			case READY:
			case APPROXIMATE_BASELINE:
			default:
				return null;
		}
	}

	private JPanel buildLegendRow()
	{
		JPanel legendRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		legendRow.setOpaque(false);
		legendRow.setAlignmentX(LEFT_ALIGNMENT);
		legendRow.add(buildLegendBadge("Ready", getStatusColor(PromotionStatus.READY), "Promotion is currently actionable."));
		legendRow.add(buildLegendBadge("Approx baseline", getStatusColor(PromotionStatus.APPROXIMATE_BASELINE), "Actionable, but baseline date is approximate."));
		legendRow.add(buildLegendBadge("Not in WOM group", getStatusColor(PromotionStatus.NO_WOM_MATCH), "Not found in configured WOM group snapshot."));
		legendRow.add(buildLegendBadge("XP pending", getStatusColor(PromotionStatus.XP_NOT_FETCHED), "Matched in WOM, waiting for XP hydration."));
		legendRow.add(buildLegendBadge("Unknown rank", getStatusColor(PromotionStatus.UNKNOWN_RANK), "Current rank not in configured ladder."));
		return legendRow;
	}

	private JPanel buildFilterRow()
	{
		JPanel filterRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		filterRow.setOpaque(false);
		filterRow.setAlignmentX(LEFT_ALIGNMENT);

		ButtonGroup group = new ButtonGroup();
		configureFilterButton(allFilterButton, LocalFilter.ALL, group);
		configureFilterButton(readyFilterButton, LocalFilter.READY, group);
		configureFilterButton(needsAttentionFilterButton, LocalFilter.NEEDS_ATTENTION, group);
		configureFilterButton(missingFilterButton, LocalFilter.MISSING, group);
		allFilterButton.setSelected(true);

		JLabel filterLabel = new JLabel("Quick filters:");
		filterLabel.setForeground(Color.LIGHT_GRAY);
		filterLabel.setFont(FontManager.getRunescapeSmallFont());

		filterRow.add(filterLabel);
		filterRow.add(allFilterButton);
		filterRow.add(readyFilterButton);
		filterRow.add(needsAttentionFilterButton);
		filterRow.add(missingFilterButton);
		return filterRow;
	}

	private JPanel buildSearchRow()
	{
		JPanel searchRow = new JPanel(new BorderLayout(4, 0));
		searchRow.setOpaque(false);
		searchRow.setAlignmentX(LEFT_ALIGNMENT);

		JLabel searchLabel = new JLabel("Search:");
		searchLabel.setForeground(Color.LIGHT_GRAY);
		searchLabel.setFont(FontManager.getRunescapeSmallFont());

		searchField.setColumns(PANEL_TEXT_COLUMNS);
		searchField.setMaximumSize(new Dimension(Integer.MAX_VALUE, searchField.getPreferredSize().height));
		searchField.setFont(FontManager.getRunescapeSmallFont());
		searchField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchField.setForeground(Color.WHITE);
		searchField.setCaretColor(Color.WHITE);
		searchField.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.DARK_GRAY_COLOR),
			new EmptyBorder(2, 4, 2, 4)
		));
		searchField.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				renderRecords();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				renderRecords();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				renderRecords();
			}
		});

		searchRow.add(searchLabel, BorderLayout.WEST);
		searchRow.add(searchField, BorderLayout.CENTER);
		return searchRow;
	}

	private void configureWomSyncLabel()
	{
		womSyncLabel.setAlignmentX(LEFT_ALIGNMENT);
		womSyncLabel.setForeground(Color.LIGHT_GRAY);
		womSyncLabel.setFont(FontManager.getRunescapeSmallFont());
	}

	private void configureInfoArea()
	{
		infoArea.setEditable(false);
		infoArea.setLineWrap(true);
		infoArea.setWrapStyleWord(true);
		infoArea.setHighlighter(null);
		infoArea.setFocusable(false);
		infoArea.setOpaque(false);
		infoArea.setForeground(Color.LIGHT_GRAY);
		infoArea.setFont(FontManager.getRunescapeSmallFont());
		infoArea.setBorder(new EmptyBorder(4, 0, 0, 0));
		infoArea.setColumns(PANEL_TEXT_COLUMNS);
		infoArea.setRows(INFO_AREA_ROWS);
		infoArea.setAlignmentX(LEFT_ALIGNMENT);
		int infoAreaLineHeight = infoArea.getFontMetrics(infoArea.getFont()).getHeight();
		int infoAreaMaxHeight = infoAreaLineHeight * INFO_AREA_ROWS + 6;
		infoArea.setMaximumSize(new Dimension(Integer.MAX_VALUE, infoAreaMaxHeight));
	}

	private JLabel buildLegendBadge(String label, Color color, String tooltip)
	{
		JLabel badge = new JLabel(label, SwingConstants.CENTER);
		badge.setOpaque(true);
		badge.setForeground(Color.WHITE);
		badge.setBackground(color);
		badge.setBorder(new EmptyBorder(1, 5, 1, 5));
		badge.setFont(FontManager.getRunescapeSmallFont());
		badge.setToolTipText(tooltip);
		return badge;
	}

	private void configureFilterButton(JToggleButton button, LocalFilter filter, ButtonGroup group)
	{
		group.add(button);
		button.setFocusable(false);
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setMargin(new Insets(1, 6, 1, 6));
		button.addActionListener(e ->
		{
			activeFilter = filter;
			updateFilterButtonStyles();
			renderRecords();
		});
	}

	private void updateFilterButtonStyles()
	{
		styleFilterButton(allFilterButton, activeFilter == LocalFilter.ALL);
		styleFilterButton(readyFilterButton, activeFilter == LocalFilter.READY);
		styleFilterButton(needsAttentionFilterButton, activeFilter == LocalFilter.NEEDS_ATTENTION);
		styleFilterButton(missingFilterButton, activeFilter == LocalFilter.MISSING);
	}

	private void styleFilterButton(JToggleButton button, boolean active)
	{
		button.setOpaque(true);
		button.setForeground(Color.WHITE);
		button.setBackground(active ? new Color(66, 133, 244) : new Color(69, 90, 100));
		button.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
	}

	private void updateWomSyncLabel()
	{
		long now = System.currentTimeMillis();
		String text;
		String tooltip;

		if (womGroupId <= 0)
		{
			text = "WOM group: not configured";
			tooltip = "Set a Wise Old Man group ID to enable group matching and XP hydration.";
		}
		else if (womLastSuccessfulSyncMillis <= 0L)
		{
			text = "WOM group " + womGroupId + " | Sync pending";
			tooltip = "No successful WOM group sync yet for group " + womGroupId + ".";
		}
		else
		{
			String relative = formatRelativeAge(now - womLastSuccessfulSyncMillis);
			String absolute = formatAbsoluteTimestamp(womLastSuccessfulSyncMillis);
			text = "WOM group " + womGroupId + " | Synced " + relative;
			tooltip = "Last successful WOM group sync: " + absolute;
		}

		long remainingCooldownSeconds = Math.max(0L, TimeUnit.MILLISECONDS.toSeconds(hydrationCooldownUntilMillis - now));
		if (remainingCooldownSeconds > 0L)
		{
			text += " | Cooldown " + remainingCooldownSeconds + "s";
			String reason = hydrationCooldownReason == null || hydrationCooldownReason.isBlank()
				? "Active cooldown"
				: hydrationCooldownReason;
			tooltip = tooltip + " | " + reason + " (" + remainingCooldownSeconds + "s remaining)";
		}

		womSyncLabel.setText(text);
		womSyncLabel.setToolTipText(tooltip);
	}

	private String formatRelativeAge(long ageMillis)
	{
		long safeAge = Math.max(0L, ageMillis);
		long seconds = TimeUnit.MILLISECONDS.toSeconds(safeAge);
		if (seconds < 60L)
		{
			return seconds + "s ago";
		}

		long minutes = TimeUnit.SECONDS.toMinutes(seconds);
		if (minutes < 60L)
		{
			return minutes + "m ago";
		}

		long hours = TimeUnit.MINUTES.toHours(minutes);
		if (hours < 24L)
		{
			return hours + "h ago";
		}

		long days = TimeUnit.HOURS.toDays(hours);
		return days + "d ago";
	}

	private String formatAbsoluteTimestamp(long timestampMillis)
	{
		return Instant.ofEpochMilli(timestampMillis)
			.atZone(ZoneId.systemDefault())
			.format(SYNC_TIMESTAMP_FORMAT);
	}

	private void renderRecords()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::renderRecords);
			return;
		}

		JScrollPane scrollPane = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, this);
		int scrollValue = captureScrollValue(scrollPane);

		String searchTerm = searchField.getText();
		List<PromotionRecord> filteredRecords = sourceRecords.stream()
			.filter(record -> matchesLocalFilter(record, activeFilter))
			.filter(record -> matchesSearch(record, searchTerm))
			.collect(Collectors.toList());

		content.removeAll();
		if (sourceRecords.isEmpty())
		{
			content.add(makeInfoRow("No visible members to show."));
		}
		else if (filteredRecords.isEmpty())
		{
			content.add(makeInfoRow("No members match the current quick filter/search."));
		}
		else
		{
			int displayed = 0;
			for (PromotionRecord record : filteredRecords)
			{
				if (currentMaxDisplayed > 0 && displayed >= currentMaxDisplayed)
				{
					content.add(makeInfoRow("Showing first " + currentMaxDisplayed + " of " + filteredRecords.size() + " matching members."));
					break;
				}

				content.add(createRecordRow(record));
				displayed++;
			}
		}

		content.revalidate();
		content.repaint();
		revalidate();
		repaint();
		restoreScrollValue(scrollPane, scrollValue);
	}

	private boolean matchesSearch(PromotionRecord record, String searchTerm)
	{
		if (record == null)
		{
			return false;
		}

		if (searchTerm == null || searchTerm.isBlank())
		{
			return true;
		}

		String username = record.getUsername();
		if (username == null || username.isBlank())
		{
			return false;
		}

		return username.toLowerCase(Locale.ENGLISH)
			.contains(searchTerm.toLowerCase(Locale.ENGLISH).trim());
	}

	private int captureScrollValue(JScrollPane scrollPane)
	{
		if (scrollPane == null)
		{
			return -1;
		}

		JScrollBar verticalBar = scrollPane.getVerticalScrollBar();
		if (verticalBar == null)
		{
			return -1;
		}

		return verticalBar.getValue();
	}

	private void restoreScrollValue(JScrollPane scrollPane, int previousValue)
	{
		if (scrollPane == null || previousValue < 0)
		{
			return;
		}

		SwingUtilities.invokeLater(() ->
		{
			JScrollBar verticalBar = scrollPane.getVerticalScrollBar();
			if (verticalBar == null)
			{
				return;
			}

			int maxValue = Math.max(0, verticalBar.getMaximum() - verticalBar.getVisibleAmount());
			verticalBar.setValue(Math.min(previousValue, maxValue));
		});
	}

	private JButton configureButton(JButton button, Runnable action)
	{
		button.setFocusable(false);
		button.setMargin(new Insets(2, 6, 2, 6));
		button.addActionListener(e ->
		{
			if (action != null)
			{
				action.run();
			}
		});
		return button;
	}

	private JComponent createRecordRow(PromotionRecord record)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
			new EmptyBorder(6, 6, 6, 6)));

		JPanel topRow = new JPanel(new BorderLayout(8, 0));
		topRow.setOpaque(false);

		JLabel nameLabel = new JLabel(record.getUsername());
		nameLabel.setForeground(Color.WHITE);
		nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 13f));

		JLabel statusLabel = makeBadge(record.getStatus());
		topRow.add(nameLabel, BorderLayout.WEST);
		topRow.add(statusLabel, BorderLayout.EAST);

		JTextArea rankLine = makeLine("Rank", formatRankLine(record));
		JTextArea progressLine = makeLine("Progress", "Months: " + record.getMonthsInClan() + " | XP: " + PromotionEvaluator.formatXp(record.getXpGained()));
		JTextArea baselineLine = makeLine("Baseline", formatBaselineLine(record));

		row.add(topRow);
		row.add(Box.createVerticalStrut(4));
		row.add(rankLine);
		row.add(progressLine);
		row.add(baselineLine);

		JTextArea reasonLine = null;
		String reasonText = buildReasonText(
			record,
			womGroupId,
			hydrationCooldownUntilMillis,
			hydrationCooldownReason,
			System.currentTimeMillis()
		);
		if (reasonText != null && !reasonText.isBlank())
		{
			reasonLine = makeLine("Reason", reasonText);
			row.add(reasonLine);
		}

		JPopupMenu menu = buildMemberMenu(record);
		installPopup(row, menu);
		installPopup(topRow, menu);
		installPopup(nameLabel, menu);
		installPopup(statusLabel, menu);
		installPopup(rankLine, menu);
		installPopup(progressLine, menu);
		installPopup(baselineLine, menu);
		if (reasonLine != null)
		{
			installPopup(reasonLine, menu);
		}

		return row;
	}

	private JTextArea makeLine(String label, String value)
	{
		JTextArea area = new JTextArea(label + ": " + value);
		area.setEditable(false);
		area.setLineWrap(true);
		area.setWrapStyleWord(true);
		area.setHighlighter(null);
		area.setFocusable(false);
		area.setOpaque(false);
		area.setForeground(Color.LIGHT_GRAY);
		area.setFont(FontManager.getRunescapeSmallFont());
		area.setColumns(PANEL_TEXT_COLUMNS);
		area.setBorder(new EmptyBorder(0, 0, 0, 0));
		area.setAlignmentX(LEFT_ALIGNMENT);
		Dimension preferredSize = area.getPreferredSize();
		area.setMaximumSize(new Dimension(Integer.MAX_VALUE, preferredSize.height + 2));
		return area;
	}

	private JLabel makeBadge(PromotionStatus status)
	{
		JLabel badge = new JLabel(status.getDisplayName(), SwingConstants.CENTER);
		badge.setOpaque(true);
		badge.setForeground(Color.WHITE);
		badge.setBackground(getStatusColor(status));
		badge.setBorder(new EmptyBorder(2, 6, 2, 6));
		badge.setFont(FontManager.getRunescapeSmallFont());
		badge.setToolTipText(getStatusTooltip(status));
		return badge;
	}

	private String getStatusTooltip(PromotionStatus status)
	{
		switch (status)
		{
			case READY:
				return "Meets month + XP requirements for the recommended rank.";
			case APPROXIMATE_BASELINE:
				return "Likely ready, but baseline start date is approximate.";
			case NO_WOM_MATCH:
				return "Not found in the configured WOM group snapshot. This does not always mean no WOM profile exists.";
			case XP_NOT_FETCHED:
				return "Matched in WOM, but XP baseline/current data has not been fully hydrated yet.";
			case UNKNOWN_RANK:
				return "Current clan rank did not map to the configured rank ladder.";
			case NOT_READY:
			default:
				return "Tracked, but not yet meeting the next rank requirements.";
		}
	}

	private JComponent makeInfoRow(String message)
	{
		JTextArea area = new JTextArea(message);
		area.setEditable(false);
		area.setLineWrap(true);
		area.setWrapStyleWord(true);
		area.setHighlighter(null);
		area.setOpaque(true);
		area.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		area.setForeground(Color.LIGHT_GRAY);
		area.setFont(FontManager.getRunescapeSmallFont());
		area.setColumns(PANEL_TEXT_COLUMNS);
		area.setBorder(new EmptyBorder(8, 8, 8, 8));
		area.setMaximumSize(new Dimension(Integer.MAX_VALUE, area.getPreferredSize().height + 4));
		return area;
	}

	private String formatRankLine(PromotionRecord record)
	{
		String nextRank = record.getNextRankCandidate() == null || record.getNextRankCandidate().isBlank()
			? "No further rank"
			: record.getNextRankCandidate();

		return record.getCurrentRank() + " -> " + nextRank;
	}

	private String formatBaselineLine(PromotionRecord record)
	{
		String joinDate = formatDate(record.getJoinDate());
		String baselineDate = formatDate(record.getBaselineDate());
		if (record.isApproximateBaseline() && baselineDate != null)
		{
			baselineDate += " ~";
		}

		return "Join: " + orDash(joinDate)
			+ " | Base: " + orDash(baselineDate)
			+ " | Curr: " + PromotionEvaluator.formatXp(record.getCurrentXp());
	}

	private String formatDate(LocalDate value)
	{
		return value == null ? null : value.toString();
	}

	private String orDash(String value)
	{
		return value == null || value.isBlank() ? "-" : value;
	}

	private Color getStatusColor(PromotionStatus status)
	{
		switch (status)
		{
			case READY:
				return new Color(56, 142, 60);
			case APPROXIMATE_BASELINE:
				return new Color(251, 140, 0);
			case NO_WOM_MATCH:
				return new Color(239, 108, 0);
			case XP_NOT_FETCHED:
				return new Color(96, 125, 139);
			case UNKNOWN_RANK:
				return new Color(198, 40, 40);
			case NOT_READY:
			default:
				return new Color(84, 110, 122);
		}
	}

	private JPopupMenu buildMemberMenu(PromotionRecord record)
	{
		JPopupMenu menu = new JPopupMenu();

		JMenuItem openWomItem = new JMenuItem("Open WOM profile");
		openWomItem.addActionListener(e ->
		{
			if (openWomProfileConsumer != null)
			{
				openWomProfileConsumer.accept(record);
			}
		});
		menu.add(openWomItem);

		JMenuItem ignoreItem = new JMenuItem("Ignore " + record.getUsername());
		ignoreItem.addActionListener(e ->
		{
			if (ignoreUserConsumer != null)
			{
				ignoreUserConsumer.accept(record.getUsername());
			}
		});
		menu.add(ignoreItem);
		return menu;
	}

	private void installPopup(JComponent component, JPopupMenu menu)
	{
		component.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mousePressed(java.awt.event.MouseEvent event)
			{
				maybeShowPopup(event);
			}

			@Override
			public void mouseReleased(java.awt.event.MouseEvent event)
			{
				maybeShowPopup(event);
			}

			private void maybeShowPopup(java.awt.event.MouseEvent event)
			{
				if (event.isPopupTrigger() && menu != null)
				{
					menu.show(event.getComponent(), event.getX(), event.getY());
					event.consume();
				}
			}
		});
	}
}
