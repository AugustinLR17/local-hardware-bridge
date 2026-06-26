package main

import (
	"bytes"
	"context"
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

// httpClient is shared across all requests. A per-request context with its own
// timeout is used on top of this for cancellation; the client timeout is a
// backstop covering the entire request lifecycle.
var httpClient = &http.Client{Timeout: 10 * time.Second}

// requestTimeout bounds a single HTTP request via context.
const requestTimeout = 8 * time.Second

type tickMsg time.Time

type healthResp struct {
	Status      string          `json:"status"`
	AppName     string          `json:"appName"`
	Version     string          `json:"version"`
	Services    map[string]bool `json:"services"`
	Connections map[string]int  `json:"connections"`
}

type printerMapping struct {
	Type               string `json:"type"`
	Name               string `json:"name"`
	AutoRotate         bool   `json:"autoRotate"`
	ResetImageableArea bool   `json:"resetImageableArea"`
	ForceDPI           int    `json:"forceDPI"`
}

type printerConfig struct {
	Enabled         bool             `json:"enabled"`
	AutoAddUnknown  bool             `json:"autoAddUnknownType"`
	FallbackDefault bool             `json:"fallbackToDefault"`
	Mappings        []printerMapping `json:"mappings"`
}

type serialMapping struct {
	Type         string `json:"type"`
	Name         string `json:"name"`
	BaudRate     int    `json:"baudRate"`
	NumDataBits  int    `json:"numDataBits"`
	NumStopBits  int    `json:"numStopBits"`
	Parity       int    `json:"parity"`
	ReadMultiple bool   `json:"readMultipleBytes"`
	ReadCharset  string `json:"readCharset"`
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
	serverURL      string
	token          string
	menuIndex      int
	health         *healthResp
	printerCfg     *printerConfig
	serialCfg      *serialConfig
	serialStatus   []serialStatus
	err            string
	status         string
	confirmRestart bool
	width          int
	height         int
	loaded         bool
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
	token := os.Getenv("LHB_TOKEN")

	args := os.Args[1:]
	for i := 0; i < len(args); i++ {
		switch {
		case args[i] == "--server" && i+1 < len(args):
			serverURL = args[i+1]
			i++
		case args[i] == "--token" && i+1 < len(args):
			token = args[i+1]
			i++
		case strings.HasPrefix(args[i], "http"):
			serverURL = args[i]
		}
	}

	p := tea.NewProgram(initialModel(serverURL, token), tea.WithAltScreen())
	if _, err := p.Run(); err != nil {
		fmt.Fprintf(os.Stderr, "Error: %v\n", err)
		os.Exit(1)
	}
}

func initialModel(serverURL, token string) model {
	return model{
		serverURL: serverURL,
		token:     token,
	}
}

func (m model) Init() tea.Cmd {
	return tea.Batch(
		tickCmd(),
		fetchHealthCmd(m.serverURL, m.token),
		fetchPrinterConfigCmd(m.serverURL, m.token),
		fetchSerialConfigCmd(m.serverURL, m.token),
		fetchSerialStatusCmd(m.serverURL, m.token),
	)
}

func (m model) refreshAllCmd() tea.Cmd {
	return tea.Batch(
		fetchHealthCmd(m.serverURL, m.token),
		fetchPrinterConfigCmd(m.serverURL, m.token),
		fetchSerialConfigCmd(m.serverURL, m.token),
		fetchSerialStatusCmd(m.serverURL, m.token),
	)
}

func tickCmd() tea.Cmd {
	return tea.Tick(5*time.Second, func(t time.Time) tea.Msg {
		return tickMsg(t)
	})
}

func fetchHealthCmd(url, token string) tea.Cmd {
	return func() tea.Msg {
		ctx, cancel := context.WithTimeout(context.Background(), requestTimeout)
		defer cancel()
		h := &healthResp{}
		if err := fetchJSON(ctx, http.MethodGet, url+"/system/health", token, nil, h); err != nil {
			return errMsg{err: err.Error()}
		}
		return healthMsg{health: h}
	}
}

func fetchPrinterConfigCmd(url, token string) tea.Cmd {
	return func() tea.Msg {
		ctx, cancel := context.WithTimeout(context.Background(), requestTimeout)
		defer cancel()
		c := &printerConfig{}
		if err := fetchJSON(ctx, http.MethodGet, url+"/printer/mappings", token, nil, c); err != nil {
			return errMsg{err: err.Error()}
		}
		return printerConfigMsg{config: c}
	}
}

func fetchSerialConfigCmd(url, token string) tea.Cmd {
	return func() tea.Msg {
		ctx, cancel := context.WithTimeout(context.Background(), requestTimeout)
		defer cancel()
		c := &serialConfig{}
		if err := fetchJSON(ctx, http.MethodGet, url+"/serial/mappings", token, nil, c); err != nil {
			return errMsg{err: err.Error()}
		}
		return serialConfigMsg{config: c}
	}
}

func fetchSerialStatusCmd(url, token string) tea.Cmd {
	return func() tea.Msg {
		ctx, cancel := context.WithTimeout(context.Background(), requestTimeout)
		defer cancel()
		s := []serialStatus{}
		if err := fetchJSON(ctx, http.MethodGet, url+"/serial/status", token, nil, &s); err != nil {
			return errMsg{err: err.Error()}
		}
		return serialStatusMsg{status: s}
	}
}

func togglePrinterCmd(url, token string, enabled bool) tea.Cmd {
	return func() tea.Msg {
		ctx, cancel := context.WithTimeout(context.Background(), requestTimeout)
		defer cancel()
		body := bytes.NewReader([]byte(fmt.Sprintf(`{"enabled":%t}`, enabled)))
		c := &printerConfig{}
		if err := fetchJSON(ctx, http.MethodPut, url+"/printer/enabled", token, body, c); err != nil {
			return errMsg{err: err.Error()}
		}
		return printerConfigMsg{config: c}
	}
}

func toggleSerialCmd(url, token string, enabled bool) tea.Cmd {
	return func() tea.Msg {
		ctx, cancel := context.WithTimeout(context.Background(), requestTimeout)
		defer cancel()
		body := bytes.NewReader([]byte(fmt.Sprintf(`{"enabled":%t}`, enabled)))
		c := &serialConfig{}
		if err := fetchJSON(ctx, http.MethodPut, url+"/serial/enabled", token, body, c); err != nil {
			return errMsg{err: err.Error()}
		}
		return serialConfigMsg{config: c}
	}
}

func restartCmd(url, token string) tea.Cmd {
	return func() tea.Msg {
		ctx, cancel := context.WithTimeout(context.Background(), requestTimeout)
		defer cancel()
		if err := fetchJSON(ctx, http.MethodPost, url+"/system/restart.json", token, bytes.NewReader([]byte("{}")), nil); err != nil {
			return errMsg{err: err.Error()}
		}
		return actionMsg{msg: "Server restart requested"}
	}
}

// fetchJSON issues an HTTP request with the shared client, attaching the bearer
// token (if any) and a JSON content type for bodies. Non-2xx responses become
// errors carrying the endpoint and a bounded snippet of the response body. When
// v is nil the body is discarded.
func fetchJSON(ctx context.Context, method, url, token string, body io.Reader, v interface{}) error {
	req, err := http.NewRequestWithContext(ctx, method, url, body)
	if err != nil {
		return fmt.Errorf("building request for %s: %w", url, err)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}

	resp, err := httpClient.Do(req)
	if err != nil {
		return fmt.Errorf("requesting %s: %w", url, err)
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		snippet, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		return fmt.Errorf("%s returned HTTP %d: %s", url, resp.StatusCode, strings.TrimSpace(string(snippet)))
	}

	if v == nil {
		return nil
	}

	if err := json.NewDecoder(resp.Body).Decode(v); err != nil {
		return fmt.Errorf("decoding response from %s: %w", url, err)
	}
	return nil
}

type healthMsg struct{ health *healthResp }
type printerConfigMsg struct{ config *printerConfig }
type serialConfigMsg struct{ config *serialConfig }
type serialStatusMsg struct{ status []serialStatus }
type actionMsg struct{ msg string }
type errMsg struct{ err string }

func (m model) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	switch msg := msg.(type) {
	case tea.WindowSizeMsg:
		m.width = msg.Width
		m.height = msg.Height

	case tickMsg:
		return m, tea.Batch(tickCmd(), m.refreshAllCmd())

	case healthMsg:
		m.health = msg.health
		m.loaded = true
		m.err = ""
		return m, nil

	case printerConfigMsg:
		m.printerCfg = msg.config
		m.err = ""
		return m, nil

	case serialConfigMsg:
		m.serialCfg = msg.config
		m.err = ""
		return m, nil

	case serialStatusMsg:
		m.serialStatus = msg.status
		m.err = ""
		return m, nil

	case actionMsg:
		m.status = msg.msg
		m.err = ""
		return m, nil

	case errMsg:
		m.err = msg.err
		return m, nil

	case tea.KeyMsg:
		key := msg.String()

		// Restart confirmation intercepts all keys.
		if m.confirmRestart {
			switch key {
			case "y", "Y":
				m.confirmRestart = false
				m.status = "Restarting..."
				return m, restartCmd(m.serverURL, m.token)
			default:
				m.confirmRestart = false
				m.status = "Restart cancelled"
				return m, nil
			}
		}

		switch key {
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
		case "right", "tab", "l":
			m.menuIndex = (m.menuIndex + 1) % len(pages)
		case "left", "shift+tab", "h":
			m.menuIndex = (m.menuIndex - 1 + len(pages)) % len(pages)
		case "r":
			m.status = "Refreshing..."
			return m, m.refreshAllCmd()
		case "e":
			// Toggle the enabled state of the subsystem on the current page.
			switch page(m.menuIndex) {
			case pagePrinters:
				if m.printerCfg != nil {
					return m, togglePrinterCmd(m.serverURL, m.token, !m.printerCfg.Enabled)
				}
			case pageSerial:
				if m.serialCfg != nil {
					return m, toggleSerialCmd(m.serverURL, m.token, !m.serialCfg.Enabled)
				}
			}
		case "R":
			m.confirmRestart = true
			return m, nil
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

	var banner string
	if m.confirmRestart {
		banner = styleWarning.Render("Restart the server? Press y to confirm, any other key to cancel.") + "\n"
	} else if m.err != "" {
		banner = styleDanger.Render("Error: "+m.err) + "\n"
	} else if m.status != "" {
		banner = styleSuccess.Render(m.status) + "\n"
	}

	footer := m.renderFooter()

	out := fmt.Sprintf("%s\n%s\n%s%s\n%s", title, menu, banner, content, footer)

	// Constrain to the terminal width so content does not overflow narrow
	// terminals (truncates over-wide lines rather than wrapping them).
	if m.width > 0 {
		out = lipgloss.NewStyle().MaxWidth(m.width).Render(out)
	}
	return out
}

func (m model) renderFooter() string {
	help := "1-4/←→/tab: switch • r: refresh • e: toggle enabled • R: restart • q: quit"
	return styleDim.Render(fmt.Sprintf("[%s] %s", m.serverURL, help))
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
			s += fmt.Sprintf("  %s → %s  (%d baud, %d-%d-%s, %s)\n",
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
	s += styleDim.Render("  PUT  ") + styleValue.Render("/printer/enabled") + "\n"
	s += styleDim.Render("  GET  ") + styleValue.Render("/serial/mappings") + "\n"
	s += styleDim.Render("  POST ") + styleValue.Render("/serial/mappings") + "\n"
	s += styleDim.Render("  PUT  ") + styleValue.Render("/serial/enabled") + "\n"
	s += styleDim.Render("  GET  ") + styleValue.Render("/serial/status") + "\n"
	s += styleDim.Render("  POST ") + styleValue.Render("/system/restart.json") + "\n"

	s += "\n"
	s += styleHeader.Render(" Server ")
	s += "\n\n"
	s += fmt.Sprintf("  URL: %s\n", styleValue.Render(m.serverURL))
	if m.token != "" {
		s += fmt.Sprintf("  Auth: %s\n", styleSuccess.Render("Bearer token set"))
	} else {
		s += fmt.Sprintf("  Auth: %s\n", styleDim.Render("none"))
	}

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
