package com.clanpromotiontracker;

import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Insets;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;

final class ClanPromotionTrackerPanel extends PluginPanel
{
	private static final int PANEL_TEXT_COLUMNS = 22;
	private static final int INFO_AREA_ROWS = 3;

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
	private final Consumer<String> ignoreUserConsumer;
	private String lastRenderKey = "";

	ClanPromotionTrackerPanel(
		Runnable refreshAction,
		Runnable stopAction,
		Runnable copyAction,
		Runnable saveAction,
		Runnable copyReportAction,
		Runnable saveReportAction,
		Runnable exportCacheAction,
		Runnable importCacheAction,
		Consumer<String> ignoreUserConsumer)
	{
		this.ignoreUserConsumer = ignoreUserConsumer;

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

		headerPanel.add(titleLabel);
		headerPanel.add(Box.createVerticalStrut(6));
		headerPanel.add(buttonRow);
		headerPanel.add(Box.createVerticalStrut(4));
		headerPanel.add(reportButtonRow);
		headerPanel.add(Box.createVerticalStrut(4));
		headerPanel.add(cacheButtonRow);
		headerPanel.add(infoArea);
		add(headerPanel, BorderLayout.NORTH);

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		content.setOpaque(false);
		add(content, BorderLayout.CENTER);
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
			String renderKey = buildRenderKey(records, maxDisplayed);
			if (renderKey.equals(lastRenderKey))
			{
				return;
			}

			JScrollPane scrollPane = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, this);
			int scrollValue = captureScrollValue(scrollPane);
			content.removeAll();
			if (records == null || records.isEmpty())
			{
				content.add(makeInfoRow("No visible members to show."));
			}
			else
			{
				int displayed = 0;
				for (PromotionRecord record : records)
				{
					if (maxDisplayed > 0 && displayed >= maxDisplayed)
					{
						content.add(makeInfoRow("Showing the first " + maxDisplayed + " members."));
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
			lastRenderKey = renderKey;
		});
	}

	private String buildRenderKey(List<PromotionRecord> records, int maxDisplayed)
	{
		if (records == null || records.isEmpty())
		{
			return "empty|" + maxDisplayed;
		}

		StringBuilder key = new StringBuilder(records.size() * 64);
		key.append("max=").append(maxDisplayed).append('|');
		int displayed = 0;
		for (PromotionRecord record : records)
		{
			if (maxDisplayed > 0 && displayed >= maxDisplayed)
			{
				key.append("truncated|");
				break;
			}

			key.append(record.getUsername()).append('|')
				.append(record.getCurrentRank()).append('|')
				.append(record.getNextRankCandidate()).append('|')
				.append(record.getStatus()).append('|')
				.append(record.getMonthsInClan()).append('|')
				.append(record.getXpGained()).append('|')
				.append(record.getBaselineDate()).append('|')
				.append(record.getBaselineXp()).append('|')
				.append(record.getCurrentXp()).append('|')
				.append(record.isApproximateBaseline()).append(';');
			displayed++;
		}
		return key.toString();
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

		JPopupMenu menu = buildMemberMenu(record.getUsername());
		installPopup(row, menu);
		installPopup(topRow, menu);
		installPopup(nameLabel, menu);
		installPopup(statusLabel, menu);
		installPopup(rankLine, menu);
		installPopup(progressLine, menu);
		installPopup(baselineLine, menu);

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
		return badge;
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

	private JPopupMenu buildMemberMenu(String username)
	{
		JPopupMenu menu = new JPopupMenu();
		JMenuItem ignoreItem = new JMenuItem("Ignore " + username);
		ignoreItem.addActionListener(e ->
		{
			if (ignoreUserConsumer != null)
			{
				ignoreUserConsumer.accept(username);
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
