using System;
using UnityEngine;
using UnityEngine.EventSystems;
using UnityEngine.UI;

namespace Festa.Avatar
{
    public sealed class AvatarColorPicker : MonoBehaviour, IPointerDownHandler, IDragHandler
    {
        public enum PickerMode { Hue, SaturationValue, HueSaturationWheel, Value }
        [SerializeField] PickerMode _mode;
        RawImage _image;
        Texture2D _texture;
        Action<float, float> _changed;
        float _hue;
        float _saturation = 1f;
        RectTransform _marker;

        public void Configure(PickerMode mode, Action<float, float> changed)
        { _mode = mode; _changed = changed; _image = GetComponent<RawImage>(); BuildMarker(); Rebuild(0, 1); SetSelection(0, mode == PickerMode.Value ? 1 : 0); }

        public void Rebuild(float hue, float saturation = 1f)
        {
            _hue = hue;
            _saturation = saturation;
            int width = _mode == PickerMode.Hue || _mode == PickerMode.Value ? 16 : _mode == PickerMode.HueSaturationWheel ? 128 : 192;
            int height = _mode == PickerMode.HueSaturationWheel ? 128 : 96;
            if (_texture == null || _texture.width != width || _texture.height != height) { if (_texture) Destroy(_texture); _texture = new Texture2D(width, height, TextureFormat.RGBA32, false) { wrapMode = TextureWrapMode.Clamp, filterMode = FilterMode.Bilinear }; }
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
            {
                float nx = x / (float)(width - 1), ny = y / (float)(height - 1);
                Color color;
                if(_mode==PickerMode.Hue)color=Color.HSVToRGB(ny,1,1);
                else if(_mode==PickerMode.Value)color=Color.HSVToRGB(_hue,_saturation,ny);
                else if(_mode==PickerMode.HueSaturationWheel)
                {
                    float dx=nx*2-1,dy=ny*2-1,r=Mathf.Sqrt(dx*dx+dy*dy);
                    color=r>1?Color.clear:Color.HSVToRGB(Mathf.Repeat(Mathf.Atan2(dy,dx)/(Mathf.PI*2),1),r,1);
                }
                else color=Color.HSVToRGB(_hue,nx,ny);
                _texture.SetPixel(x,y,color);
            }
            _texture.Apply(false); _image.texture = _texture;
        }

        void BuildMarker()
        {
            var marker = new GameObject("Selection Marker", typeof(RectTransform), typeof(Image), typeof(Outline)).GetComponent<Image>();
            marker.transform.SetParent(transform, false); marker.color = new Color(1, 1, 1, .92f); marker.raycastTarget = false;
            _marker = marker.rectTransform; _marker.anchorMin = _marker.anchorMax = new Vector2(.5f, .5f); _marker.sizeDelta = new Vector2(11, 11);
            var outline = marker.GetComponent<Outline>(); outline.effectColor = new Color(0, 0, 0, .9f); outline.effectDistance = new Vector2(1.5f, -1.5f);
        }

        public void SetSelection(float first, float second)
        {
            if (!_marker) return;
            Vector2 normalized;
            if (_mode == PickerMode.HueSaturationWheel)
            {
                float angle = first * Mathf.PI * 2f;
                normalized = new Vector2(.5f + Mathf.Cos(angle) * second * .5f, .5f + Mathf.Sin(angle) * second * .5f);
            }
            else normalized = new Vector2(.5f, second);
            _marker.anchorMin = _marker.anchorMax = normalized; _marker.anchoredPosition = Vector2.zero;
        }

        public void OnPointerDown(PointerEventData eventData) => Pick(eventData);
        public void OnDrag(PointerEventData eventData) => Pick(eventData);
        void Pick(PointerEventData eventData)
        {
            var rect = (RectTransform)transform;
            if (!RectTransformUtility.ScreenPointToLocalPointInRectangle(rect, eventData.position, eventData.pressEventCamera, out var p)) return;
            var n = Rect.PointToNormalized(rect.rect, p); n.x = Mathf.Clamp01(n.x); n.y = Mathf.Clamp01(n.y);
            if(_mode==PickerMode.HueSaturationWheel)
            {
                float dx=n.x*2-1,dy=n.y*2-1;float saturation=Mathf.Clamp01(Mathf.Sqrt(dx*dx+dy*dy));
                float hue=Mathf.Repeat(Mathf.Atan2(dy,dx)/(Mathf.PI*2),1);SetSelection(hue,saturation);_changed?.Invoke(hue,saturation);
            }
            else { SetSelection(n.x,n.y); _changed?.Invoke(n.x,n.y); }
        }
        void OnDestroy() { if (_texture) Destroy(_texture); }
    }
}
