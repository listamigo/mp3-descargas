DARK_QSS = """
QMainWindow, QWidget {
    background-color: #121212;
    color: #e1e1e1;
}
QMainWindow > QWidget {
    background-color: transparent;
}
QLabel#sectionHeader {
    color: #FF0000;
    font-size: 14px;
    font-weight: bold;
    padding: 12px 16px 4px;
}
QLabel#titleLabel {
    font-size: 13px;
    font-weight: 500;
    color: #e1e1e1;
}
QLabel#artistLabel {
    font-size: 11px;
    color: #b3b3b3;
}
QLabel#durationLabel {
    font-size: 11px;
    color: #535353;
}
QLabel#pathLabel {
    font-size: 10px;
    color: #535353;
}
QLabel#errorLabel {
    font-size: 11px;
    color: #E74C3C;
}
QLineEdit {
    background-color: #282828;
    color: #e1e1e1;
    border: 1px solid #404040;
    border-radius: 6px;
    padding: 8px 12px;
    font-size: 13px;
}
QLineEdit:focus {
    border: 1px solid #FF0000;
}
QPushButton#searchBtn {
    background-color: #FF0000;
    color: #000000;
    border: 1px solid #FF0000;
    border-radius: 20px;
    padding: 8px 20px;
    font-weight: bold;
    font-size: 12px;
}
QPushButton#searchBtn:hover {
    background-color: #E70000;
}
QPushButton#searchBtn:disabled {
    background-color: #333333;
    color: #666666;
}
QPushButton#downloadBtn {
    background-color: #FF0000;
    color: #000000;
    border: 1px solid #FF0000;
    border-radius: 16px;
    padding: 6px 16px;
    font-weight: bold;
    font-size: 11px;
}
QPushButton#downloadBtn:hover {
    background-color: #E70000;
}
QPushButton#playBtn {
    background-color: transparent;
    border: none;
    padding: 4px;
}
QPushButton#cancelBtn {
    background-color: rgba(255, 0, 0, 0.15);
    color: #FF0000;
    border: none;
    border-radius: 12px;
    padding: 4px 10px;
    font-size: 11px;
}
QPushButton#cancelBtn:hover {
    background-color: rgba(255, 0, 0, 0.25);
}
QPushButton#openBtn, QPushButton#retryBtn {
    background-color: rgba(255, 0, 0, 0.15);
    color: #FF0000;
    border: none;
    border-radius: 12px;
    padding: 4px 10px;
    font-size: 11px;
}
QPushButton#openBtn:hover, QPushButton#retryBtn:hover {
    background-color: rgba(255, 0, 0, 0.25);
}
QPushButton#loadMoreBtn {
    background-color: transparent;
    color: #b3b3b3;
    border: 1px solid #404040;
    border-radius: 4px;
    margin: 12px 48px;
    font-size: 12px;
}
QPushButton#loadMoreBtn:hover {
    color: #e1e1e1;
    border-color: #535353;
}
QPushButton#settingsBtn {
    background-color: transparent;
    border: none;
    font-size: 18px;
    color: #b3b3b3;
    padding: 4px 8px;
}
QPushButton#settingsBtn:hover {
    color: #e1e1e1;
}
QTabWidget::pane {
    border: none;
    background-color: transparent;
}
QTabBar::tab {
    background-color: #1e1e1e;
    color: #b3b3b3;
    padding: 10px 24px;
    font-size: 13px;
    border: none;
}
QTabBar::tab:selected {
    color: #FF0000;
    border-bottom: 2px solid #FF0000;
}
QTabBar::tab:hover {
    color: #e1e1e1;
}
QScrollBar:vertical {
    background-color: #1e1e1e;
    width: 8px;
    border: none;
}
QScrollBar::handle:vertical {
    background-color: #404040;
    border-radius: 4px;
    min-height: 30px;
}
QScrollBar::handle:vertical:hover {
    background-color: #535353;
}
QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical {
    height: 0;
}
QProgressBar {
    background-color: #282828;
    border: none;
    border-radius: 2px;
    height: 4px;
}
QProgressBar::chunk {
    background-color: #FF0000;
    border-radius: 2px;
}
QSlider::groove:horizontal {
    background: #404040;
    height: 4px;
    border-radius: 2px;
}
QSlider::handle:horizontal {
    background: #FF0000;
    width: 16px;
    height: 16px;
    margin: -6px 0;
    border-radius: 8px;
}
QSlider::sub-page:horizontal {
    background: #FF0000;
    border-radius: 2px;
}
QComboBox {
    background-color: #282828;
    color: #e1e1e1;
    border: 1px solid #404040;
    border-radius: 6px;
    padding: 6px 12px;
    font-size: 12px;
}
QComboBox:drop-down {
    border: none;
}
QComboBox:down-arrow {
    image: none;
}
QComboBox QAbstractItemView {
    background-color: #282828;
    color: #e1e1e1;
    selection-background-color: #FF0000;
}
QFileDialog {
    background-color: #121212;
    color: #e1e1e1;
}
"""

LIGHT_QSS = """
QMainWindow, QWidget {
    background-color: #FFFFFF;
    color: #1a1a1a;
}
QMainWindow > QWidget {
    background-color: transparent;
}
QLabel#sectionHeader {
    color: #FF0000;
    font-size: 14px;
    font-weight: bold;
    padding: 12px 16px 4px;
}
QLabel#titleLabel {
    font-size: 13px;
    font-weight: 500;
    color: #1a1a1a;
}
QLabel#artistLabel {
    font-size: 11px;
    color: #666666;
}
QLabel#durationLabel {
    font-size: 11px;
    color: #999999;
}
QLabel#pathLabel {
    font-size: 10px;
    color: #999999;
}
QLabel#errorLabel {
    font-size: 11px;
    color: #FF0000;
}
QLineEdit {
    background-color: #f0f0f0;
    color: #1a1a1a;
    border: 1px solid #cccccc;
    border-radius: 6px;
    padding: 8px 12px;
    font-size: 13px;
}
QLineEdit:focus {
    border: 1px solid #FF0000;
}
QPushButton#searchBtn {
    background-color: #FF0000;
    color: #FFFFFF;
    border: 1px solid #FF0000;
    border-radius: 20px;
    padding: 8px 20px;
    font-weight: bold;
    font-size: 12px;
}
QPushButton#searchBtn:hover {
    background-color: #E70000;
}
QPushButton#searchBtn:disabled {
    background-color: #cccccc;
    color: #999999;
}
QPushButton#downloadBtn {
    background-color: #FF0000;
    color: #FFFFFF;
    border: 1px solid #FF0000;
    border-radius: 16px;
    padding: 6px 16px;
    font-weight: bold;
    font-size: 11px;
}
QPushButton#downloadBtn:hover {
    background-color: #E70000;
}
QPushButton#playBtn {
    background-color: transparent;
    border: none;
    padding: 4px;
}
QPushButton#cancelBtn {
    background-color: rgba(255, 0, 0, 0.1);
    color: #FF0000;
    border: none;
    border-radius: 12px;
    padding: 4px 10px;
    font-size: 11px;
}
QPushButton#cancelBtn:hover {
    background-color: rgba(255, 0, 0, 0.2);
}
QPushButton#openBtn, QPushButton#retryBtn {
    background-color: rgba(255, 0, 0, 0.1);
    color: #FF0000;
    border: none;
    border-radius: 12px;
    padding: 4px 10px;
    font-size: 11px;
}
QPushButton#openBtn:hover, QPushButton#retryBtn:hover {
    background-color: rgba(255, 0, 0, 0.2);
}
QPushButton#loadMoreBtn {
    background-color: transparent;
    color: #666666;
    border: 1px solid #cccccc;
    border-radius: 4px;
    margin: 12px 48px;
    font-size: 12px;
}
QPushButton#loadMoreBtn:hover {
    color: #1a1a1a;
    border-color: #999999;
}
QPushButton#settingsBtn {
    background-color: transparent;
    border: none;
    font-size: 18px;
    color: #666666;
    padding: 4px 8px;
}
QPushButton#settingsBtn:hover {
    color: #1a1a1a;
}
QTabWidget::pane {
    border: none;
    background-color: transparent;
}
QTabBar::tab {
    background-color: #f5f5f5;
    color: #666666;
    padding: 10px 24px;
    font-size: 13px;
    border: none;
}
QTabBar::tab:selected {
    color: #FF0000;
    border-bottom: 2px solid #FF0000;
}
QTabBar::tab:hover {
    color: #1a1a1a;
}
QScrollBar:vertical {
    background-color: #f5f5f5;
    width: 8px;
    border: none;
}
QScrollBar::handle:vertical {
    background-color: #cccccc;
    border-radius: 4px;
    min-height: 30px;
}
QScrollBar::handle:vertical:hover {
    background-color: #999999;
}
QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical {
    height: 0;
}
QProgressBar {
    background-color: #f0f0f0;
    border: none;
    border-radius: 2px;
    height: 4px;
}
QProgressBar::chunk {
    background-color: #FF0000;
    border-radius: 2px;
}
QSlider::groove:horizontal {
    background: #cccccc;
    height: 4px;
    border-radius: 2px;
}
QSlider::handle:horizontal {
    background: #FF0000;
    width: 16px;
    height: 16px;
    margin: -6px 0;
    border-radius: 8px;
}
QSlider::sub-page:horizontal {
    background: #FF0000;
    border-radius: 2px;
}
QComboBox {
    background-color: #f0f0f0;
    color: #1a1a1a;
    border: 1px solid #cccccc;
    border-radius: 6px;
    padding: 6px 12px;
    font-size: 12px;
}
QComboBox:drop-down {
    border: none;
}
QComboBox:down-arrow {
    image: none;
}
QComboBox QAbstractItemView {
    background-color: #FFFFFF;
    color: #1a1a1a;
    selection-background-color: #FF0000;
    selection-color: #FFFFFF;
}
QFileDialog {
    background-color: #FFFFFF;
    color: #1a1a1a;
}
"""

GLASS_QSS = """
QMainWindow, QWidget {
    background-color: #121212;
    color: #e1e1e1;
}
QMainWindow > QWidget {
    background-color: transparent;
}
QLabel#sectionHeader {
    color: #FF0000;
    font-size: 14px;
    font-weight: bold;
    padding: 12px 16px 4px;
}
QLabel#titleLabel {
    font-size: 13px;
    font-weight: 500;
    color: #FFFFFF;
}
QLabel#artistLabel {
    font-size: 11px;
    color: rgba(255, 255, 255, 0.7);
}
QLabel#durationLabel {
    font-size: 11px;
    color: rgba(255, 255, 255, 0.5);
}
QLabel#pathLabel {
    font-size: 10px;
    color: rgba(255, 255, 255, 0.5);
}
QLabel#errorLabel {
    font-size: 11px;
    color: #FF0000;
}
QLineEdit {
    background-color: rgba(255, 255, 255, 0.1);
    color: #FFFFFF;
    border: 1px solid rgba(255, 255, 255, 0.2);
    border-radius: 6px;
    padding: 8px 12px;
    font-size: 13px;
}
QLineEdit:focus {
    border: 1px solid #FF0000;
}
QPushButton#searchBtn {
    background-color: rgba(255, 0, 0, 0.8);
    color: #FFFFFF;
    border: 1px solid rgba(255, 0, 0, 0.9);
    border-radius: 20px;
    padding: 8px 20px;
    font-weight: bold;
    font-size: 12px;
}
QPushButton#searchBtn:hover {
    background-color: #E70000;
}
QPushButton#searchBtn:disabled {
    background-color: rgba(255, 255, 255, 0.1);
    color: rgba(255, 255, 255, 0.4);
}
QPushButton#downloadBtn {
    background-color: rgba(255, 0, 0, 0.8);
    color: #FFFFFF;
    border: 1px solid rgba(255, 0, 0, 0.9);
    border-radius: 16px;
    padding: 6px 16px;
    font-weight: bold;
    font-size: 11px;
}
QPushButton#downloadBtn:hover {
    background-color: #E70000;
}
QPushButton#playBtn {
    background-color: transparent;
    border: none;
    padding: 4px;
}
QPushButton#cancelBtn {
    background-color: rgba(255, 0, 0, 0.2);
    color: #FF0000;
    border: 1px solid rgba(255, 0, 0, 0.1);
    border-radius: 12px;
    padding: 4px 10px;
    font-size: 11px;
}
QPushButton#cancelBtn:hover {
    background-color: rgba(255, 0, 0, 0.35);
}
QPushButton#openBtn, QPushButton#retryBtn {
    background-color: rgba(255, 0, 0, 0.3);
    color: #FF0000;
    border: 1px solid rgba(255, 0, 0, 0.1);
    border-radius: 12px;
    padding: 4px 10px;
    font-size: 11px;
}
QPushButton#openBtn:hover, QPushButton#retryBtn:hover {
    background-color: rgba(255, 0, 0, 0.5);
}
QPushButton#loadMoreBtn {
    background-color: rgba(255, 255, 255, 0.05);
    color: rgba(255, 255, 255, 0.7);
    border: 1px solid rgba(255, 255, 255, 0.15);
    border-radius: 4px;
    margin: 12px 48px;
    font-size: 12px;
}
QPushButton#loadMoreBtn:hover {
    color: #FFFFFF;
    border-color: rgba(255, 255, 255, 0.3);
}
QPushButton#settingsBtn {
    background-color: transparent;
    border: none;
    font-size: 18px;
    color: rgba(255, 255, 255, 0.7);
    padding: 4px 8px;
}
QPushButton#settingsBtn:hover {
    color: #FFFFFF;
}
QTabWidget::pane {
    border: none;
    background-color: transparent;
}
QTabBar::tab {
    background-color: rgba(255, 255, 255, 0.08);
    color: rgba(255, 255, 255, 0.7);
    padding: 10px 24px;
    font-size: 13px;
    border: none;
    border-top-left-radius: 8px;
    border-top-right-radius: 8px;
}
QTabBar::tab:selected {
    color: #FF0000;
    background-color: rgba(255, 255, 255, 0.15);
    border-bottom: 2px solid #FF0000;
}
QTabBar::tab:hover {
    color: #FFFFFF;
}
QScrollBar:vertical {
    background-color: rgba(255, 255, 255, 0.05);
    width: 8px;
    border: none;
}
QScrollBar::handle:vertical {
    background-color: rgba(255, 255, 255, 0.2);
    border-radius: 4px;
    min-height: 30px;
}
QScrollBar::handle:vertical:hover {
    background-color: rgba(255, 255, 255, 0.35);
}
QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical {
    height: 0;
}
QProgressBar {
    background-color: rgba(255, 255, 255, 0.1);
    border: none;
    border-radius: 2px;
    height: 4px;
}
QProgressBar::chunk {
    background-color: #FF0000;
    border-radius: 2px;
}
QSlider::groove:horizontal {
    background: rgba(255, 255, 255, 0.2);
    height: 4px;
    border-radius: 2px;
}
QSlider::handle:horizontal {
    background: #FF0000;
    width: 16px;
    height: 16px;
    margin: -6px 0;
    border-radius: 8px;
}
QSlider::sub-page:horizontal {
    background: #FF0000;
    border-radius: 2px;
}
QComboBox {
    background-color: rgba(255, 255, 255, 0.1);
    color: #FFFFFF;
    border: 1px solid rgba(255, 255, 255, 0.2);
    border-radius: 6px;
    padding: 6px 12px;
    font-size: 12px;
}
QComboBox:drop-down {
    border: none;
}
QComboBox:down-arrow {
    image: none;
}
QComboBox QAbstractItemView {
    background-color: rgba(30, 30, 30, 0.95);
    color: #FFFFFF;
    selection-background-color: #FF0000;
}
QFileDialog {
    background-color: #1a1a1a;
    color: #e1e1e1;
}
"""


SAKURA_QSS = """
QMainWindow, QWidget {
    background-color: #121212;
    color: #e1e1e1;
}
QMainWindow > QWidget {
    background-color: transparent;
}
QLabel#sectionHeader {
    color: #FF0000;
    font-size: 14px;
    font-weight: bold;
    padding: 12px 16px 4px;
}
QLabel#titleLabel {
    font-size: 13px;
    font-weight: 500;
    color: #e1e1e1;
}
QLabel#artistLabel {
    font-size: 11px;
    color: #b3b3b3;
}
QLabel#durationLabel {
    font-size: 11px;
    color: #535353;
}
QLabel#pathLabel {
    font-size: 10px;
    color: #535353;
}
QLabel#errorLabel {
    font-size: 11px;
    color: #FF0000;
}
QLineEdit {
    background-color: #282828;
    color: #e1e1e1;
    border: 1px solid #404040;
    border-radius: 6px;
    padding: 8px 12px;
    font-size: 13px;
}
QLineEdit:focus {
    border: 1px solid #FF0000;
}
QPushButton#searchBtn {
    background-color: #FF0000;
    color: #FFFFFF;
    border: 1px solid #FF0000;
    border-radius: 20px;
    padding: 8px 20px;
    font-weight: bold;
    font-size: 12px;
}
QPushButton#searchBtn:hover {
    background-color: #E70000;
}
QPushButton#searchBtn:disabled {
    background-color: #333333;
    color: #666666;
}
QPushButton#downloadBtn {
    background-color: #FF0000;
    color: #FFFFFF;
    border: 1px solid #FF0000;
    border-radius: 16px;
    padding: 6px 16px;
    font-weight: bold;
    font-size: 11px;
}
QPushButton#downloadBtn:hover {
    background-color: #E70000;
}
QPushButton#playBtn {
    background-color: transparent;
    border: none;
    padding: 4px;
}
QPushButton#cancelBtn {
    background-color: rgba(255, 0, 0, 0.15);
    color: #FF0000;
    border: none;
    border-radius: 12px;
    padding: 4px 10px;
    font-size: 11px;
}
QPushButton#cancelBtn:hover {
    background-color: rgba(255, 0, 0, 0.25);
}
QPushButton#openBtn, QPushButton#retryBtn {
    background-color: rgba(255, 0, 0, 0.15);
    color: #FF0000;
    border: none;
    border-radius: 12px;
    padding: 4px 10px;
    font-size: 11px;
}
QPushButton#openBtn:hover, QPushButton#retryBtn:hover {
    background-color: rgba(255, 0, 0, 0.25);
}
QPushButton#loadMoreBtn {
    background-color: transparent;
    color: #b3b3b3;
    border: 1px solid #404040;
    border-radius: 4px;
    margin: 12px 48px;
    font-size: 12px;
}
QPushButton#loadMoreBtn:hover {
    color: #e1e1e1;
    border-color: #535353;
}
QPushButton#settingsBtn {
    background-color: transparent;
    border: none;
    font-size: 18px;
    color: #b3b3b3;
    padding: 4px 8px;
}
QPushButton#settingsBtn:hover {
    color: #e1e1e1;
}
QTabWidget::pane {
    border: none;
    background-color: transparent;
}
QTabBar::tab {
    background-color: #1e1e1e;
    color: #b3b3b3;
    padding: 10px 24px;
    font-size: 13px;
    border: none;
}
QTabBar::tab:selected {
    color: #FF0000;
    background-color: transparent;
    border-bottom: 2px solid #FF0000;
}
QTabBar::tab:hover {
    color: #e1e1e1;
}
QScrollBar:vertical {
    background-color: #1e1e1e;
    width: 8px;
    border: none;
}
QScrollBar::handle:vertical {
    background-color: #404040;
    border-radius: 4px;
    min-height: 30px;
}
QScrollBar::handle:vertical:hover {
    background-color: #535353;
}
QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical {
    height: 0;
}
QProgressBar {
    background-color: #282828;
    border: none;
    border-radius: 2px;
    height: 4px;
}
QProgressBar::chunk {
    background-color: #FF0000;
    border-radius: 2px;
}
QSlider::groove:horizontal {
    background: #404040;
    height: 4px;
    border-radius: 2px;
}
QSlider::handle:horizontal {
    background: #FF0000;
    width: 16px;
    height: 16px;
    margin: -6px 0;
    border-radius: 8px;
}
QSlider::sub-page:horizontal {
    background: #FF0000;
    border-radius: 2px;
}
QComboBox {
    background-color: #282828;
    color: #e1e1e1;
    border: 1px solid #404040;
    border-radius: 6px;
    padding: 6px 12px;
    font-size: 12px;
}
QComboBox:drop-down {
    border: none;
}
QComboBox QAbstractItemView {
    background-color: #282828;
    color: #e1e1e1;
    selection-background-color: #FF0000;
}
QFileDialog {
    background-color: #121212;
    color: #e1e1e1;
}
"""


def get_theme_qss(theme_name: str) -> str:
    return {
        "dark": DARK_QSS,
        "light": LIGHT_QSS,
        "glass": GLASS_QSS,
        "sakura": SAKURA_QSS,
    }.get(theme_name, DARK_QSS)
