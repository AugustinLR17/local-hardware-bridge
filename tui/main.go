package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"time"

	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/lipgloss"
)

var (
	styleTitle = lipgloss.NewStyle().
			Bold(true).
			Foreground(lipgloss.Color("#6c72ff")).
			MarginBottom(1)

	styleHeader = lipgloss.NewStyle().
			Bold(true).
			Foreground(lipgloss.Color("#e2e4f0")).
			Background(lipgloss.Color("#242836")).
			Padding(0, 1)

	styleKey = lipgloss.NewStyle().
			Foreground(lipgloss.Color("#6c72ff"))

	styleValue = lipgloss.NewStyle().
			Foreground(lipgloss.Color("#e2e4f0"))

	styleDim = lipgloss.NewStyle().
			Foreground(lipgloss.Color("#8b8fa8"))

	styleSuccess = lipgloss.NewStyle().
			Foreground(lipgloss.Color("#4ade80"))

	styleDanger = lipgloss.NewStyle().
			Foreground(lipgloss.Color("#f87171"))

	styleWarning = lipgloss.NewStyle().
			Foreground(lipgloss.Color("#fbbf24"))

	styleBox = lipgloss.NewStyle().
			Border(lipgloss.RoundedBorder()).
			BorderForeground(lipgloss.Color("#2e3348")).
			Padding(0, 1)

	styleSelected = lipgloss.NewStyle().
			Foreground(lipgloss.Color("#6c72ff")).
			Bold(true)

	styleMenuItem = lipgloss.NewStyle().
			PaddingLeft(2)
)

type tickMsg time.Time

type healthResp struct {
	Status      string            `json:"status"`
	AppName     string            `json:"appName"`
	Version     string            `json:"version"`
	Services    map[string]bool   `json:"services"`
	Connections map[string]int    `json:"connections"`
}

type printerMapping struct {
	Type                string `json:"type"`
	Name                string `json:"name"`
	AutoRotate          bool   `json:"autoRotate"`
	ResetImageableArea  bool   `json:"resetImageableArea"`
	ForceDPI            int    `json:"forceDPI"`
}

type printerConfig struct {
	Enabled           bool             `json:"enabled"`
	AutoAddUnknown    bool             `json:"autoAddUnknownType"`
	FallbackDefault   bool             `json:"fallbackToDefault"`
	Mappings          []printerMapping `json:"mappings"`
}

type serialMapping struct {
	Type             string `json:"type"`
	Name             string `json:"name"`
	BaudRate         int    `json:"baudRate"`
	NumDataBits      int    `json:"numDataBits"`
	NumStopBits      int    `json:"numStopBits"`
	Parity           int    `json:"parity"`
	ReadMultiple     bool   `json:"readMultipleBytes"`
	ReadCharset      string `json:"readCharset"`
}

type serialConfig struct {
	Enabled  bool            `json:"enabled"`
	Mappings []serialMapping `json:"mappings"`
}

type serialStatus struct {
	Port        string `json:"port"`
	Description string `json:"description"`
	Open        bool   `json:"open"`
	Mapped      bool   `json:"mapped"`
	Type        string `json:"type,omitempty"`
}

type model struct {
	serverURL    string
	menuIndex    int
	health       *healthResp
	printerCfg   *printerConfig
	serialCfg    *serialConfig
	serialStatus []serialStatus
	err          string
	width        int
	height       int
	loaded       bool
}

type page int

const (
	pageDashboard page = iota
	pagePrinters
	pageSerial
	pageConfig
)

var pages = []string{"Dashboard", "Printers", "Serial Ports", "Configuration"}

func (p page) String() string {
	if int(p) < len(pages) {
		return pages[p]
	}
	return ""
}

func main() {
	serverURL := "http://127.0.0.1:12212"
	if len(os.Args) > 1 {
		if strings.HasPrefix(os.Args[1], "http") {
			serverURL = os.Args[1]
		} else if os.Args[1] == "--server" && len(os.Args) > 2 {
			serverURL = os.Args[2]
		}
	}

	p := tea.NewProgram(initialModel(serverURL), tea.WithAltScreen())
	if _, err := p.Run(); err != nil {
		fmt.Fprintf(os.Stderr, "Error: %v\n", err)
		os.Exit(1)
	}
}

func initialModel(serverURL string) model {
	return model{
		serverURL: serverURL,
	}
}

func (m model) Init() tea.Cmd {
	return tea.Batch(tickCmd(), fetchHealthCmd(m.serverURL), fetchPrinterConfigCmd(m.serverURL), fetchSerialConfigCmd(m.serverURL), fetchSerialStatusCmd(m.serverURL))
}

func tickCmd() tea.Cmd {
	return tea.Tick(5*time.Second, func(t time.Time) tea.Msg {
		return tickMsg(t)
	})
}

func fetchHealthCmd(url string) tea.Cmd {
	return func() tea.Msg {
		h := &healthResp{}
		err := fetchJSON(url+"/system/health", h)
		if err != nil {
			return errMsg{err: err.Error()}
		}
		return healthMsg{health: h}
	}
}

func fetchPrinterConfigCmd(url string) tea.Cmd {
	return func() tea.Msg {
		c := &printerConfig{}
		err := fetchJSON(url+"/printer/mappings", c)
		if err != nil {
			return errMsg{err: err.Error()}
		}
		return printerConfigMsg{config: c}
	}
}

func fetchSerialConfigCmd(url string) tea.Cmd {
	return func() tea.Msg {
		c := &serialConfig{}
		err := fetchJSON(url+"/serial/mappings", c)
		if err != nil {
			return errMsg{err: err.Error()}
		}
		return serialConfigMsg{config: c}
	}
}

func fetchSerialStatusCmd(url string) tea.Cmd {
	return func() tea.Msg {
		s := []serialStatus{}
		err := fetchJSON(url+"/serial/status", &s)
		if err != nil {
			return errMsg{err: err.Error()}
		}
		return serialStatusMsg{status: s}
	}
}

func fetchJSON(url string, v interface{}) error {
	client := &http.Client{Timeout: 5 * time.Second}
	resp, err := client.Get(url)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("HTTP %d: %s", resp.StatusCode, string(body))
	}

	return json.NewDecoder(resp.Body).Decode(v)
}

type healthMsg struct{ health *healthResp }
type printerConfigMsg struct{ config *printerConfig }
type serialConfigMsg struct{ config *serialConfig }
type serialStatusMsg struct{ status []serialStatus }
type errMsg struct{ err string }

func (m model) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	switch msg := msg.(type) {
	case tea.WindowSizeMsg:
		m.width = msg.Width
		m.height = msg.Height

	case tickMsg:
		return m, tea.Batch(tickCmd(), fetchHealthCmd(m.serverURL), fetchSerialStatusCmd(m.serverURL))

	case healthMsg:
		m.health = msg.health
		m.loaded = true
		return m, nil

	case printerConfigMsg:
		m.printerCfg = msg.config
		return m, nil

	case serialConfigMsg:
		m.serialCfg = msg.config
		return m, nil

	case serialStatusMsg:
		m.serialStatus = msg.status
		return m, nil

	case errMsg:
		m.err = msg.err
		return m, nil

	case tea.KeyMsg:
		switch msg.String() {
		case "ctrl+c", "q":
			return m, tea.Quit
		case "1":
			m.menuIndex = 0
		case "2":
			m.menuIndex = 1
		case "3":
			m.menuIndex = 2
		case "4":
			m.menuIndex = 3
		case "r":
			return m, tea.Batch(fetchHealthCmd(m.serverURL), fetchPrinterConfigCmd(m.serverURL), fetchSerialConfigCmd(m.serverURL), fetchSerialStatusCmd(m.serverURL))
		}
	}

	return m, nil
}

func (m model) View() string {
	if !m.loaded && m.health == nil && m.err == "" {
		return styleDim.Render("Connecting to " + m.serverURL + " ...")
	}

	title := styleTitle.Render("⚡ Local Hardware Bridge TUI")
	menu := m.renderMenu()

	var content string
	switch page(m.menuIndex) {
	case pageDashboard:
		content = m.renderDashboard()
	case pagePrinters:
		content = m.renderPrinters()
	case pageSerial:
		content = m.renderSerial()
	case pageConfig:
		content = m.renderConfig()
	}

	if m.err != "" {
		content = styleDanger.Render("Error: "+m.err) + "\n" + content
	}

	footer := styleDim.Render(fmt.Sprintf("[%s] Press 1-4 to navigate • r to refresh • q to quit", m.serverURL))

	return fmt.Sprintf("%s\n%s\n%s\n%s", title, menu, content, footer)
}

func (m model) renderMenu() string {
	items := make([]string, len(pages))
	for i, p := range pages {
		if i == m.menuIndex {
			items[i] = styleSelected.Render(fmt.Sprintf(" %d. %s ", i+1, p))
		} else {
			items[i] = styleDim.Render(fmt.Sprintf(" %d. %s ", i+1, p))
		}
	}
	return lipgloss.JoinHorizontal(lipgloss.Top, items...)
}

func (m model) renderDashboard() string {
	if m.health == nil {
		return styleDim.Render("No health data")
	}

	statusIcon := "●"
	statusStyle := styleSuccess
	if m.health.Status != "UP" {
		statusStyle = styleDanger
	}

	s := styleHeader.Render(" System Status ")
	s += "\n\n"
	s += fmt.Sprintf("  %s %s  •  %s v%s\n", statusStyle.Render(statusIcon), statusStyle.Render(m.health.Status), m.health.AppName, m.health.Version)

	s += "\n"
	s += styleHeader.Render(" Services ")
	s += "\n\n"
	for name, active := range m.health.Services {
		icon := "●"
		if active {
			s += fmt.Sprintf("  %s %s\n", styleSuccess.Render(icon), styleValue.Render(name))
		} else {
			s += fmt.Sprintf("  %s %s\n", styleDanger.Render(icon), styleValue.Render(name))
		}
	}

	s += "\n"
	s += styleHeader.Render(" Connections ")
	s += "\n\n"
	if len(m.health.Connections) == 0 {
		s += styleDim.Render("  No active connections\n")
	} else {
		for ch, count := range m.health.Connections {
			s += fmt.Sprintf("  %s  %d client(s)\n", styleKey.Render(ch), count)
		}
	}

	return styleBox.Render(s)
}

func (m model) renderPrinters() string {
	if m.printerCfg == nil {
		return styleDim.Render("No printer data")
	}

	s := styleHeader.Render(" Printer Service ")
	s += "\n\n"

	icon := "●"
	if m.printerCfg.Enabled {
		s += fmt.Sprintf("  %s %s\n\n", styleSuccess.Render(icon), styleSuccess.Render("Enabled"))
	} else {
		s += fmt.Sprintf("  %s %s\n\n", styleDanger.Render(icon), styleDanger.Render("Disabled"))
	}

	s += fmt.Sprintf("  Auto-add unknown: %s\n", boolStr(m.printerCfg.AutoAddUnknown))
	s += fmt.Sprintf("  Fallback default: %s\n\n", boolStr(m.printerCfg.FallbackDefault))

	s += styleHeader.Render(" Mappings ")
	s += "\n\n"
	if len(m.printerCfg.Mappings) == 0 {
		s += styleDim.Render("  No printer mappings configured\n")
	} else {
		for _, pm := range m.printerCfg.Mappings {
			s += fmt.Sprintf("  %s → %s  (rotate: %s, DPI: %d)\n",
				styleKey.Render(pm.Type),
				styleValue.Render(pm.Name),
				boolStr(pm.AutoRotate),
				pm.ForceDPI)
		}
	}

	return styleBox.Render(s)
}

func (m model) renderSerial() string {
	if m.serialCfg == nil {
		return styleDim.Render("No serial data")
	}

	s := styleHeader.Render(" Serial Service ")
	s += "\n\n"

	icon := "●"
	if m.serialCfg.Enabled {
		s += fmt.Sprintf("  %s %s\n\n", styleSuccess.Render(icon), styleSuccess.Render("Enabled"))
	} else {
		s += fmt.Sprintf("  %s %s\n\n", styleDanger.Render(icon), styleDanger.Render("Disabled"))
	}

	s += styleHeader.Render(" Port Status ")
	s += "\n\n"
	if len(m.serialStatus) == 0 {
		s += styleDim.Render("  No serial ports detected\n")
	} else {
		for _, ps := range m.serialStatus {
			statusIcon := "●"
			if ps.Open {
				s += fmt.Sprintf("  %s %s — %s  %s\n",
					styleSuccess.Render(statusIcon),
					styleKey.Render(ps.Port),
					styleDim.Render(ps.Description),
					styleWarning.Render("[OPEN]"))
			} else {
				s += fmt.Sprintf("  %s %s — %s  %s\n",
					styleDanger.Render(statusIcon),
					styleKey.Render(ps.Port),
					styleDim.Render(ps.Description),
					styleDim.Render("[CLOSED]"))
			}
			if ps.Mapped {
				s += fmt.Sprintf("      Mapped as: %s\n", styleValue.Render(ps.Type))
			}
		}
	}

	s += "\n"
	s += styleHeader.Render(" Mappings ")
	s += "\n\n"
	if len(m.serialCfg.Mappings) == 0 {
		s += styleDim.Render("  No serial mappings configured\n")
	} else {
		for _, sm := range m.serialCfg.Mappings {
			s += fmt.Sprintf("  %s → %s  (%d baud, %d-%d-%d, %s)\n",
				styleKey.Render(sm.Type),
				styleValue.Render(sm.Name),
				sm.BaudRate,
				sm.NumDataBits,
				sm.NumStopBits,
				parityStr(sm.Parity),
				sm.ReadCharset)
		}
	}

	return styleBox.Render(s)
}

func (m model) renderConfig() string {
	if m.health == nil {
		return styleDim.Render("No config data — use /config.json API endpoint")
	}

	s := styleHeader.Render(" API Endpoints ")
	s += "\n\n"
	s += styleDim.Render("  GET  ") + styleValue.Render("/config.json") + "\n"
	s += styleDim.Render("  PUT  ") + styleValue.Render("/config.json") + "\n"
	s += styleDim.Render("  GET  ") + styleValue.Render("/system/health") + "\n"
	s += styleDim.Render("  GET  ") + styleValue.Render("/system/version.json") + "\n"
	s += styleDim.Render("  GET  ") + styleValue.Render("/system/printers.json") + "\n"
	s += styleDim.Render("  GET  ") + styleValue.Render("/system/serials.json") + "\n"
	s += styleDim.Render("  GET  ") + styleValue.Render("/printer/mappings") + "\n"
	s += styleDim.Render("  POST ") + styleValue.Render("/printer/mappings") + "\n"
	s += styleDim.Render("  GET  ") + styleValue.Render("/serial/mappings") + "\n"
	s += styleDim.Render("  POST ") + styleValue.Render("/serial/mappings") + "\n"
	s += styleDim.Render("  GET  ") + styleValue.Render("/serial/status") + "\n"
	s += styleDim.Render("  POST ") + styleValue.Render("/system/restart.json") + "\n"

	s += "\n"
	s += styleHeader.Render(" Server ")
	s += "\n\n"
	s += fmt.Sprintf("  URL: %s\n", styleValue.Render(m.serverURL))

	return styleBox.Render(s)
}

func boolStr(b bool) string {
	if b {
		return styleSuccess.Render("yes")
	}
	return styleDim.Render("no")
}

func parityStr(p int) string {
	switch p {
	case 0:
		return "NONE"
	case 1:
		return "ODD"
	case 2:
		return "EVEN"
	case 3:
		return "MARK"
	case 4:
		return "SPACE"
	default:
		return fmt.Sprintf("%d", p)
	}
}
