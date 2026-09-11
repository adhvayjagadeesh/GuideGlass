// Port of the CameraX setup in VisionActivity.kt onto getUserMedia.
//
// On a phone this asks for the rear camera; on a laptop it gets whatever
// webcam exists. The <video> element itself is the frame source for both the
// reflex model and the Gemini encoder - no intermediate bitmap copies.

export class Camera {
  constructor(videoEl) {
    this.video = videoEl;
    this.stream = null;
    this.facing = "environment";
  }

  async start(facing = this.facing) {
    this.stop();
    this.facing = facing;
    const constraints = {
      audio: false,
      video: { facingMode: { ideal: facing }, width: { ideal: 1280 }, height: { ideal: 720 }, frameRate: { ideal: 30 } },
    };
    try {
      this.stream = await navigator.mediaDevices.getUserMedia(constraints);
    } catch (e) {
      // Laptops have no "environment" camera; fall back to any camera.
      if (facing === "environment") {
        this.stream = await navigator.mediaDevices.getUserMedia({ audio: false, video: true });
      } else throw e;
    }
    this.video.srcObject = this.stream;
    await this.video.play();
    await new Promise((r) => (this.video.readyState >= 2 ? r() : (this.video.onloadeddata = r)));
    return { width: this.video.videoWidth, height: this.video.videoHeight };
  }

  async flip() { return this.start(this.facing === "environment" ? "user" : "environment"); }

  stop() {
    this.stream?.getTracks().forEach((t) => t.stop());
    this.stream = null;
    this.video.srcObject = null;
  }

  get ready() { return !!this.stream && this.video.readyState >= 2; }
}
