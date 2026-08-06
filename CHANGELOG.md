# Changelog

## [0.5.0](https://github.com/andrewkomkov/GreenPods/compare/v0.4.0...v0.5.0) (2026-08-06)


### Features

* let the app check what the phone can do, and give the start screen a top ([#11](https://github.com/andrewkomkov/GreenPods/issues/11)) ([cf57cf2](https://github.com/andrewkomkov/GreenPods/commit/cf57cf276ca785a5f33e49b001e450882472b0b8))


### Bug Fixes

* **data:** withdraw a heart rate that has stopped arriving ([#13](https://github.com/andrewkomkov/GreenPods/issues/13)) ([b901bdf](https://github.com/andrewkomkov/GreenPods/commit/b901bdf0522ada70354657a6fa0c0fc8da03a20f))

## [0.4.0](https://github.com/andrewkomkov/GreenPods/compare/v0.3.1...v0.4.0) (2026-08-06)


### Features

* live activities — the gate and the read-only surface ([#10](https://github.com/andrewkomkov/GreenPods/issues/10)) ([4086ab1](https://github.com/andrewkomkov/GreenPods/commit/4086ab1dd91887f3c747f4b78d1d5e1ae66ddb7c))


### Documentation

* **spec:** specify live activities ([#8](https://github.com/andrewkomkov/GreenPods/issues/8)) ([edaa3db](https://github.com/andrewkomkov/GreenPods/commit/edaa3dbd7fe60b73d8edbb650561ef0f1ab0d2d3))

## [0.3.1](https://github.com/andrewkomkov/GreenPods/compare/v0.3.0...v0.3.1) (2026-08-05)


### Bug Fixes

* make heart rate start, and stop reading the head pose from a moving offset ([#6](https://github.com/andrewkomkov/GreenPods/issues/6)) ([1d76635](https://github.com/andrewkomkov/GreenPods/commit/1d76635a0e41f3612b2e271919af31ec370490f5))

## [0.3.0](https://github.com/andrewkomkov/GreenPods/compare/v0.2.1...v0.3.0) (2026-08-05)


### Features

* **app:** draw the launcher icon as a pod inside a battery ring ([b0f44ef](https://github.com/andrewkomkov/GreenPods/commit/b0f44ef5c3dcabbc25845e6ac7ae82d20097d17b))
* **app:** float the navigation bar and let the app fill the screen ([3222a17](https://github.com/andrewkomkov/GreenPods/commit/3222a1789dfed909786dce6ee305d92f33025c91))
* **app:** give the app Material motion and an Expressive navigation bar ([5bb0c95](https://github.com/andrewkomkov/GreenPods/commit/5bb0c9566169faa1062818d9926057ca600ac3de))
* build out the transport-gated app over the advertisement path ([c2345ad](https://github.com/andrewkomkov/GreenPods/commit/c2345ad79edb05b64f32cc922bac24889bc895c6))
* **controls:** state the lock once, and keep the controls visible ([405dbf6](https://github.com/andrewkomkov/GreenPods/commit/405dbf676831b27e9e9eaa8e5b6b8ac5453f6a69))
* **designsystem:** give "this phone can't do that" one language ([3300ca8](https://github.com/andrewkomkov/GreenPods/commit/3300ca81587e7cb51decf67fb42fa88be9c71936))
* **designsystem:** move onto Material 3 Expressive for real ([1a05b42](https://github.com/andrewkomkov/GreenPods/commit/1a05b42f3520ea128a872dcc557098c0b56882d4))
* **heart-rate:** read AirPods Pro 3 heart rate and write it to Health Connect ([6de9ec4](https://github.com/andrewkomkov/GreenPods/commit/6de9ec4acc288c4a64e795657f5d675797af3b37))
* open the Apple protocol channel without root ([90fd45e](https://github.com/andrewkomkov/GreenPods/commit/90fd45e1781b7996e4bc796deac40938df103d0a))
* **pods:** draw every heart-rate state as its own shape ([c0f2ae6](https://github.com/andrewkomkov/GreenPods/commit/c0f2ae68e539d0b71440e45114000ff91cbc2071))
* **pods:** make heart-rate motion carry the reading, and read as a state ([089a39b](https://github.com/andrewkomkov/GreenPods/commit/089a39bad62d7492c7d604935a8e230555f6a51e))
* **pods:** teach the nod instead of waiting for it to work ([8a1669f](https://github.com/andrewkomkov/GreenPods/commit/8a1669f31a3a9a18e5c6060cc6182937b335d822))
* **protocol:** dispatch AAP opcode 0x17 on content and reassemble frames ([727b006](https://github.com/andrewkomkov/GreenPods/commit/727b0068fbd2eba8c4f59b1350c9b26b7c0bc8e4))
* the product UI, on Material 3 Expressive ([670e392](https://github.com/andrewkomkov/GreenPods/commit/670e39243023282b3d757668727270d090ece913))


### Bug Fixes

* **bluetooth:** keep every service the accessory describes, not just the last frame ([f2fc672](https://github.com/andrewkomkov/GreenPods/commit/f2fc67212571cb18529419ebc51ed4b76224a29a))
* **bluetooth:** report ear detection by side, not by whichever bud is primary ([e3ac1ed](https://github.com/andrewkomkov/GreenPods/commit/e3ac1ed0628f56aab8484f6f5a56ffa46c5014ee))
* correlate advertisements with the paired device, and drive it all from adb ([409cd13](https://github.com/andrewkomkov/GreenPods/commit/409cd139ef22dbe8a40f349ce555b11715a75214))
* **heart-rate:** ask the accessory to describe itself, and keep identities apart ([1d2e205](https://github.com/andrewkomkov/GreenPods/commit/1d2e2059995ee732204f00fa6afb8cfba0a8d22e))
* **heart-rate:** keep the channel and the accessory's identity across reconnections ([41406b6](https://github.com/andrewkomkov/GreenPods/commit/41406b69ec117327b4d7679df7bd0e1f6eadcebf))
* **heart-rate:** make the toggle work without a channel that answers twice ([ce5e249](https://github.com/andrewkomkov/GreenPods/commit/ce5e249a793dadda7ba91ab0cfbb1043a8658445))
* **heart-rate:** stop a blocking health-store call from wedging the controller ([b833946](https://github.com/andrewkomkov/GreenPods/commit/b833946ef5656332bc1d6733edaaadca8d9cac0b))
* **heart-rate:** stop the controller awaiting the transport, which froze the spinner ([0c69478](https://github.com/andrewkomkov/GreenPods/commit/0c6947893e1a66bec7ed6c7060ab7c597758a5af))
* **pods:** ask the earbuds to describe themselves before starting head tracking ([aee26c5](https://github.com/andrewkomkov/GreenPods/commit/aee26c5d6a2d2215e0f050073e5601461744d94b))
* **pods:** give the head-gesture face a colour of its own ([38d59a3](https://github.com/andrewkomkov/GreenPods/commit/38d59a377b74cde57981391e9ef5ce95cfb90d70))
* say whose earbuds these are, and which side is which ([#4](https://github.com/andrewkomkov/GreenPods/issues/4)) ([53df587](https://github.com/andrewkomkov/GreenPods/commit/53df587af748a527e64839bbcfe20ab74e5907d1))


### Refactoring

* **settings:** drop the diagnostics surface, and name features not transports ([a0de093](https://github.com/andrewkomkov/GreenPods/commit/a0de093a4e9d4bb3d43c454f7d3e08a8dff166b5))


### Documentation

* add a designer brief for the product UI ([3636961](https://github.com/andrewkomkov/GreenPods/commit/363696165481a327da3af26fe83bc2a3e136bf13))
* close the heart-rate phases, deferring three measurements explicitly ([46df229](https://github.com/andrewkomkov/GreenPods/commit/46df229b392200e03f4247496cbadc7bcbc671df))
* decode the AirPods Pro 3 heart-rate report ([086e60a](https://github.com/andrewkomkov/GreenPods/commit/086e60a5410243511443fd576fda61739dc1d6ef))
* decode the HID service descriptors the accessory announces ([1a0f08d](https://github.com/andrewkomkov/GreenPods/commit/1a0f08d2a6d77a95ec84747deb6919a7862c4ea2))
* plan heart rate over the Apple protocol and Health Connect ([5b79260](https://github.com/andrewkomkov/GreenPods/commit/5b792600b7727beaf81da130f150c6cd63adabe0))
* record heart-rate implementation progress and what still needs hardware ([2dd1ebd](https://github.com/andrewkomkov/GreenPods/commit/2dd1ebd12d5b924b76a1d26a171e006f466f27a3))
* record the two-frame descriptor split and the heart-rate name key ([b9102a6](https://github.com/andrewkomkov/GreenPods/commit/b9102a6806887574e198203d3b1462e5015483ec))
* rule out two candidates for the HID descriptor request ([41bcc88](https://github.com/andrewkomkov/GreenPods/commit/41bcc883f372d882f6524ec228ced6e32c44bf7a))
* spec heart rate, including writing to Health Connect ([b1651a9](https://github.com/andrewkomkov/GreenPods/commit/b1651a91fa1bc18f3608b6a5794034a5b59196a6))
* spec the Apple protocol feature parity work ([cd92f7f](https://github.com/andrewkomkov/GreenPods/commit/cd92f7f0705504576daf110a63b05469bfecbbc7))
* **spec:** specify the head tracking calibration wizard ([#5](https://github.com/andrewkomkov/GreenPods/issues/5)) ([bf163ba](https://github.com/andrewkomkov/GreenPods/commit/bf163ba4cd2e096f23346bdbb1a18569a1bc14bf))
* the HID descriptors are announced per ACL link, not per channel ([13189f4](https://github.com/andrewkomkov/GreenPods/commit/13189f43edcb2d0910794732a0be604ea26006bc))

## [0.2.1](https://github.com/andrewkomkov/GreenPods/compare/v0.2.0...v0.2.1) (2026-08-03)


### Bug Fixes

* **ci:** treat blank keystore path as unsigned build ([29f436f](https://github.com/andrewkomkov/GreenPods/commit/29f436f6b5eb78d14dae49b3e1d1617dd1877841))

## [0.2.0](https://github.com/andrewkomkov/GreenPods/compare/v0.1.0...v0.2.0) (2026-08-03)


### Features

* initial GreenPods scaffold ([fc69ddd](https://github.com/andrewkomkov/GreenPods/commit/fc69ddd3f9b743c0363350698a23c29fd6409c1c))


### Bug Fixes

* guard L2CAP connect behind API 29 ([7ecd426](https://github.com/andrewkomkov/GreenPods/commit/7ecd426992dab18903114cae02b0da6156398b33))
