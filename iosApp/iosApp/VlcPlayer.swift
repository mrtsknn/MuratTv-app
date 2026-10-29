import UIKit
import VLCKitSPM
import ComposeApp

/// Boyutu değişince haber veren görünüm (küçük pencere ↔ tam ekran geçişinde oran yeniden hesaplanır).
private final class PlayerView: UIView {
    var onLayout: (() -> Void)?

    override func layoutSubviews() {
        super.layoutSubviews()
        onLayout?()
    }
}

/// VLC tabanlı oynatıcı: AVPlayer'ın açamadığı ham MPEG-TS (.ts) dahil her yayını açar.
final class VlcPlayer: NSObject, NativePlayer {
    private let playerView = PlayerView()
    var view: UIView { playerView }

    private let player = VLCMediaPlayer()
    private var aspectMode: Int32 = 0
    private var aspectString: UnsafeMutablePointer<CChar>?
    private var lastSize: CGSize = .zero

    override init() {
        super.init()
        playerView.backgroundColor = .black
        playerView.clipsToBounds = true
        player.drawable = playerView
        playerView.onLayout = { [weak self] in
            guard let self, self.playerView.bounds.size != self.lastSize else { return }
            self.lastSize = self.playerView.bounds.size
            self.applyAspect()
        }
    }

    deinit {
        player.stop()
        free(aspectString)
    }

    var state: Int32 {
        switch player.state {
        case .error, .ended: return 2
        default:
            // .buffering yayın sırasında da gelir; süre ilerliyorsa açılmış sayılır
            return player.isPlaying && player.time.intValue > 0 ? 1 : 0
        }
    }

    func play(url: String, userAgent: String?, referrer: String?) {
        player.stop()
        guard let u = URL(string: url) else { return }
        let media = VLCMedia(url: u)
        media.addOption(":network-caching=1000")
        if let ua = userAgent { media.addOption(":http-user-agent=\(ua)") }
        if let ref = referrer { media.addOption(":http-referrer=\(ref)") }
        player.media = media
        player.play()
        applyAspect()
    }

    func stop() {
        player.stop()
    }

    func setAspect(mode: Int32) {
        aspectMode = mode
        applyAspect()
    }

    // 0 = sığdır (VLC varsayılanı), 1 = doldur: ekran oranında kırp, 2 = uzat: ekran oranına zorla
    private func applyAspect() {
        let size = playerView.bounds.size
        let old = aspectString
        aspectString = nil
        if aspectMode != 0, size.width > 0, size.height > 0 {
            aspectString = strdup("\(Int(size.width)):\(Int(size.height))")
        }
        player.videoCropGeometry = aspectMode == 1 ? aspectString : nil
        player.videoAspectRatio = aspectMode == 2 ? aspectString : nil
        player.scaleFactor = 0
        free(old)
    }
}
