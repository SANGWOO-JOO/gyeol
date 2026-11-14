// 초대 메시지 (11-15 A안): 공유 시트와 클립보드 모두 같은 세 줄, 주소는 늘 마지막 줄.
// 공유 시트에 url을 따로 넘기면 OS가 주소를 앞에 붙이는 경우가 있어(Mac Chrome "복사") text 하나에 담는다.

export function inviteMessage(roomName: string, url: string): string {
  return [`🪐 ${roomName}에 네 자리 비워 뒀어`, '요즘 듣는 노래 담아 줘, 우리 취향이 별자리처럼 이어져 🎧', url].join('\n')
}
