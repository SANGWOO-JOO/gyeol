import { describe, expect, it } from 'vitest'
import { inviteMessage } from './inviteMessage'

describe('초대 메시지', () => {
  it('세 줄이고 주소는 마지막 줄', () => {
    const m = inviteMessage('우리 음악 우주', 'https://gyeol.app/rooms/AbC123xyZ9')
    expect(m.split('\n')).toEqual([
      '🪐 우리 음악 우주에 네 자리 비워 뒀어',
      '요즘 듣는 노래 담아 줘, 우리 취향이 별자리처럼 이어져 🎧',
      'https://gyeol.app/rooms/AbC123xyZ9',
    ])
  })
  it('방 이름은 받침과 관계없이 "에"를 그대로 붙인다', () => {
    expect(inviteMessage('새벽', 'u').split('\n')[0]).toBe('🪐 새벽에 네 자리 비워 뒀어')
    expect(inviteMessage('드라이브', 'u').split('\n')[0]).toBe('🪐 드라이브에 네 자리 비워 뒀어')
  })
})
