// 글꼴을 직접 올린다 (I-62: Google Fonts 요청 없음). docs/design/ci/README.md의 세 글꼴, 쓰는 굵기만.
// @fontsource CSS는 unicode-range로 잘게 나뉘어 있어, 화면에 나온 글자가 든 조각만 받는다. 라이선스: public/licenses/fonts/
import '@fontsource/noto-sans-kr/400.css'
import '@fontsource/noto-sans-kr/500.css'
import '@fontsource/noto-sans-kr/700.css'
import '@fontsource/gowun-batang/400.css'
import '@fontsource/gowun-batang/700.css'
// 워드마크(gyeol)만 쓰므로 라틴 조각만
import '@fontsource/quicksand/latin-600.css'
import '@fontsource/quicksand/latin-700.css'
